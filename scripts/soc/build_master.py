#!/usr/bin/env python3
"""生成 soc_master.json：以部件号为主键的芯片主表。

为什么不用营销名做主键：同一颗芯片在四个源里写法互不相同，且不可收敛——
  极客湾  骁龙 8 Elite Gen5
  安兔兔  骁龙8 至尊版 Gen5      （Elite 译作「至尊版」）
  鲁大师  骁龙 8 Elite Gen5      （保留 Elite）
  英文站  Snapdragon 8 Elite Gen 5（Gen 后带空格）
而设备上报的 Build.SOC_MODEL 只有部件号（SM8850-AC / MT6999 / S5E9955），
所以主键只能是部件号，营销名只进展示层。

数据来源：
  codenames/规格  socpk.com/api/chips（极客湾公开接口，157 颗，96 颗带代号）
  规格补充         soc_name_map.json（三星/华为代号补齐）
  覆盖面           antutu_soc.json 全集（271 条手机 SoC，从骁龙 425 到 2026 新品）
  CPU/频率         soc_specs.json（安兔兔 clusters 解析，215 条有核心构成）
  部件号           part_numbers.json（极客湾不收的老款与变体：骁龙 Plus、Helio P/G）
  跑分             antutu_soc.json（V10 总分/CPU/GPU）、ludashi.json（SoC 榜）
  人工覆盖         bench_meta.json 的 aliases（安兔兔译名与本站不一致的旗舰）
  常见机型         soc_knowledge.json 的 devices

输出结构：
  socs[]            一颗芯片一行
  match.exact       原始代号 → chip id
  match.loose       剥掉 -AB/-AC、V/CB、尾缀后的代号 → chip id（可能多条）
  platforms         平台代号（sun/kalama/mt6989）→ chip id

用法：
  python3 scripts/soc/build_master.py            # 优先用 bench-data/ 缓存
  python3 scripts/soc/build_master.py --fetch    # 强制联网抓极客湾
"""
import hashlib
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
CACHE = os.path.join(ROOT, 'bench-data', 'socpk_chips.json')
OUT = os.path.join(ASSETS, 'soc_master.json')
SOCPK = 'https://socpk.com/api/chips'

# ---------------- 名称归一化（Kotlin 侧 SocResolver.normalize 必须与此同构） ----------------
# 顺序敏感：长词在前，否则「超级至尊版」会被「至尊版」先吃掉
SYNONYMS = [
    ('超级至尊版', 'eliteextreme'), ('超级至尊', 'eliteextreme'),
    ('至尊版', 'elite'), ('至尊', 'elite'),
    ('骁龙', 'snapdragon'), ('天玑', 'dimensity'), ('麒麟', 'kirin'),
    ('联发科技', 'mediatek'), ('联发科', 'mediatek'),
    ('高通', 'qualcomm'), ('三星', 'samsung'), ('华为', 'hisilicon'), ('海思', 'hisilicon'),
    ('曦力', 'helio'), ('迅鲲', 'kompanio'), ('谷歌', 'google'),
    ('紫光展锐', 'unisoc'), ('展锐', 'unisoc'), ('联发科技', 'mediatek'),
    ('小米', 'xiaomi'), ('玄戒', 'xring'), ('澎湃', 'pinecone'), ('苹果', 'apple'),
]
# Pixel 手机代号，不是芯片代号。旧映射表把它们指向「谷歌 Pixel」，
# 当平台代号用会让 SoC 显示成手机名（bluejay 是 Pixel 6a，芯片是 Tensor G1）。
DEVICE_CODENAMES = {
    'sunfish', 'redfin', 'bramble', 'barbet', 'raven', 'oriole',
    'bluejay', 'panther', 'cheetah', 'lynx', 'felix', 'tangor', 'tangorpro',
}

VENDOR_WORDS = {
    'qualcomm', 'mediatek', 'samsung', 'hisilicon', 'google', 'xiaomi', 'apple',
    'unisoc', 'arm', 'amd', 'nvidia', 'broadcom', 'amlogic', 'allwinner', 'rockchip',
}


def normalize(s):
    s = (s or '').lower()
    s = re.sub(r'[()（）\[\]{}、,，/\\:：]', ' ', s)
    # 「+」不能直接删：删了之后「骁龙 8+ Gen1」与「骁龙 8 Gen1」归一化结果完全一样，
    # 865+ / 888+ / 765G 这些 Plus 型号会被并进基础款，主键就串了。
    s = s.replace('+', 'plus')
    s = re.sub(r'[\s\-_*]', '', s)
    # 「骁龙 780G 5G」「天玑 7200 5G」的 5G 是冗余后缀，留着会与不带 5G 的同名条目分家
    s = re.sub(r'5g$', '', s)
    for a, b in SYNONYMS:
        s = s.replace(a, b)
    return s


def strip_vendor(s):
    """去掉首词厂牌后再归一化：'高通 骁龙888' 与 '骁龙888' 视为同名。"""
    parts = (s or '').strip().split()
    if len(parts) > 1 and normalize(parts[0]) in VENDOR_WORDS:
        return normalize(' '.join(parts[1:]))
    return normalize(s)


# ---------------- 代号归一化 ----------------
# 高通：SM8750-AB / SM8750-AC / SM8750-3-AB / SM8650-Q-AA（地区与频率变体）
# 三星：S5E9945；联发科：MT6989 / MT6769V/CB / MT6769Z（同一平台的不同封装）
# 只剥「同芯片的不同配置」后缀：SM8650-Q-AB（车规）、SM8850-1-AD（封装序号）。
# -AB/-AC/-AD 不能剥：SM8250=865、SM8250-AB=865+、SM8250-AC=870，是不同芯片。
QCOM_TAIL = re.compile(r'(-Q-[A-Z]{2}|-\d(-[A-Z]{2})?)$', re.I)
MTK_TAIL = re.compile(r'^(MT\d{4})[A-Z]?(/[A-Z]{1,2})?$', re.I)


def code_exact(c):
    # 去空格与下划线：设备可能上报 zuma_pro，表里写 'Zuma Pro'
    return re.sub(r'[\s_]+', '', c).upper()


def code_loose(c):
    """剥到平台级：SM8750-AB→SM8750，MT6769V/CB→MT6769，MT6833P 保持（P 是天玑810 与 700 的区别）。"""
    t = code_exact(c)
    while QCOM_TAIL.search(t):
        t = QCOM_TAIL.sub('', t)
    m = MTK_TAIL.match(t)
    if m:
        base = m.group(1).upper()
        # 只有带封装后缀（V/CB、V/CZ）时才剥；裸 MT6833P 这类保留尾字母
        if m.group(2):
            t = base
    return t


# ---------------- 加载 ----------------
def load_json(path, default=None):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return default


def fetch_socpk():
    req = urllib.request.Request(SOCPK, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=40) as r:
        data = json.load(r)
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    with open(CACHE, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    return data


def split_codenames(raw):
    """极客湾把 'MT6769Z / MT6769V/CZ' 拆成 ['MT6769Z','/','MT6769V/CZ']，需要还原。"""
    out, buf = [], ''
    for x in raw or []:
        if x.strip() == '/':
            if buf:
                out.append(buf.strip())
            buf = ''
        else:
            buf = (buf + ' ' + x) if buf else x
    if buf:
        out.append(buf.strip())
    codes = [p for p in (x.strip() for x in out) if p and p != '/']
    # 复合代号（'GS401 Zuma Pro (S5P9875)'）里再抽出可单独命中的型号片段
    extra = []
    MODIFIER = ('pro', 'lite', 'plus', 'max', 'ultra', 'prime')
    for c in codes:
        tokens = [t for t in re.split(r'[\s()]+', c) if t]
        paired = set()   # 'Zuma Pro' 已作为整体收录，其首词不再单列，否则 G4 会抢走 G3 的 zuma
        for i, t in enumerate(tokens):
            if i > 0 and t.lower() in MODIFIER and re.fullmatch(r'[A-Za-z]{4,}', tokens[i - 1]):
                extra.append(tokens[i - 1] + ' ' + t)
                paired.add(tokens[i - 1])
        for part in tokens:
            if part in paired or part == c:
                continue
            numeric = re.match(r'^(SM\d|MT\d|S5E\d|SDM\d|MSM\d|GS\d{3}|S5P\d|KIRIN\d|V\d{4})', part, re.I)
            # 谷歌的平台代号是纯字母单词（zuma/whitechapel/cloudripper），设备原样上报
            word = re.fullmatch(r'[A-Za-z]{4,}', part) and part.lower() not in MODIFIER
            if numeric or word:
                extra.append(part)
    return codes + [e for e in dict.fromkeys(extra) if e not in codes]


def make_id(base):
    """代号优先做 id；纯中文名落到哈希。

    注意「天玑8000」「麒麟 8000」这类名字去掉汉字后只剩数字，
    若直接当 id 会和别的芯片撞车——去重时按 id 合并，麒麟 8000 曾被天玑 8000 吞掉。
    所以纯数字一律走哈希。
    """
    slug = re.sub(r'[^a-z0-9]+', '-', (base or '').lower()).strip('-')
    if not slug or slug.isdigit():
        return 'soc-' + hashlib.md5((base or '').encode('utf-8')).hexdigest()[:8]
    return slug


def bare_code(c):
    """完整料号 → 设备实际可能上报的裸号：MT6765V/CA → MT6765，S5E9945 → S5E9945。

    只对符合这个规律的联发科/三星料号生效。高通 -AB/-AC 是不同芯片
    （SM8250=865、-AB=865+、-AC=870），剥了会把三颗并成一颗，所以不派生。
    """
    t = code_exact(c)
    m = re.match(r'^(MT\d{4})[A-Z]?(?:/[A-Z]{1,2})?$', t)
    if m:
        return m.group(1)
    return ''


def assign_code(code, soc_id, exact, loose, assigned, conflicts):
    """挂一个代号。同一代号指向两颗不同芯片时不覆盖先到的，只记冲突。

    典型冲突：SM8150-AC 既是骁龙 855+ 也是 860（同芯改名），
    MT6899 被极客湾记为天玑 8400 而某些第三方表写成 8200。
    先到的是极客湾（规格源），后到的让步——错挂比不挂更糟。
    """
    k = code_exact(code)
    prev = assigned.get(k)
    if prev and prev != soc_id:
        conflicts.append((k, prev, soc_id))
        return False
    assigned[k] = soc_id
    exact.setdefault(k, []).append(soc_id)
    loose.setdefault(code_loose(code), []).append(soc_id)
    return True


def split_alias(name):
    """安兔兔把同平台不同调频写成一行：'天玑 8200/8200 Ultra'，拆成两个可匹配的名字。

    前缀只取到型号数字之前（'天玑'、'骁龙'、'MediaTek Dimensity'），
    不能拿整个第一段当前缀——'天玑8200' 当前缀会拼出 '天玑8200 8200 Ultra'。
    """
    if '/' not in (name or ''):
        return [name]
    parts = name.split('/')
    head = parts[0].strip()
    m = re.match(r'^([^\d]+)', head)
    prefix = m.group(1).strip() if m else ''
    out = [head]
    for part in parts[1:]:
        part = part.strip()
        if not part:
            continue
        if prefix and part.lower().startswith(prefix.lower()):
            out.append(part)
        else:
            out.append(f'{prefix} {part}'.strip())
    return out


def clusters_text(clusters):
    """[{count, label}] → '2×4.32GHz + 6×3.53GHz'，label 里已含架构名与频率。"""
    parts = []
    for c in clusters or []:
        n = c.get('count') or 0
        label = (c.get('label') or '').strip()
        if n and label:
            parts.append(f'{n}×{label}')
    return ' + '.join(parts)


def max_freq(clusters):
    best = 0.0
    for c in clusters or []:
        m = re.search(r'([\d.]+)\s*GHz', c.get('label') or '')
        if m:
            best = max(best, float(m.group(1)))
    return best


def gpu_key(name):
    """GPU 型号 → 兜底键：Adreno 830→adreno830，Mali-G715 MC7→mali715。"""
    n = (name or '').lower()
    m = re.search(r'adreno\s*(\d{3,4})', n)
    if m:
        return 'adreno' + m.group(1)
    m = re.search(r'(?:mali|immortalis)[\s-]*g?(\d{2,3})', n)
    if m:
        return 'mali' + m.group(1)
    m = re.search(r'xclipse\s*(\d{3})', n)
    if m:
        return 'xclipse' + m.group(1)
    return ''


# 名称前缀 → 厂商。原料只有名字时用它归厂（安兔兔的条目没有 brand 字段）。
# 顺序敏感：Tensor 必须先于 T，否则谷歌会被判成紫光。
BRAND_PREFIXES = [
    ('骁龙', '高通'), ('高通', '高通'), ('Qualcomm', '高通'),
    ('天玑', '联发科'), ('曦力', '联发科'), ('Helio', '联发科'), ('联发科技', '联发科'),
    ('MediaTek', '联发科'), ('MT', '联发科'),
    ('麒麟', '华为'), ('海思', '华为'), ('HiSilicon', '华为'),
    ('Exynos', '三星'), ('三星', '三星'), ('Samsung', '三星'),
    ('紫光展锐', '紫光展锐'), ('展锐', '紫光展锐'), ('Unisoc', '紫光展锐'),
    ('迅鲲', '联发科'), ('玄戒', '小米'), ('XRing', '小米'),
    ('Tensor', '谷歌'), ('Google', '谷歌'),
    ('瑞芯微', '瑞芯微'), ('RK', '瑞芯微'),
    ('晶晨', '晶晨'), ('Amlogic', '晶晨'), ('全志', '全志'), ('Allwinner', '全志'),
    ('瓴盛', '瓴盛科技'), ('杰发', '杰发科技'), ('Synaptics', 'Synaptics'),
]


def display_name(name):
    """去掉首词厂牌：'MediaTek Helio G96' → 'Helio G96'，'紫光展锐 T8200' → 'T8200'。

    界面有厂商徽章，名字里再带一遍厂牌是重复；全名仍保留在 names 里供匹配。
    """
    words = (name or '').split(' ', 1)
    if len(words) == 2 and normalize(words[0]) in VENDOR_WORDS:
        return words[1].strip()
    return name


def brand_of(name):
    n = (name or '').strip()
    for p, b in BRAND_PREFIXES:
        if n.lower().startswith(p.lower()):
            return b
    return ''


def main():
    fetch = '--fetch' in sys.argv
    chips = None if fetch else load_json(CACHE)
    if not chips:
        try:
            chips = fetch_socpk()
        except Exception as e:  # 网络抖动不能让每日刷新整体失败
            chips = load_json(CACHE)
            if not chips:
                print(f'抓取极客湾失败且无本地缓存：{e}', file=sys.stderr)
                return 1
            print(f'抓取极客湾失败，改用本地缓存：{e}', file=sys.stderr)
    namemap = load_json(os.path.join(ASSETS, 'soc_name_map.json'), {})
    part = load_json(os.path.join(ROOT, 'scripts', 'soc', 'part_numbers.json'), {})
    part_by_name = {}
    for e in part.get('entries', []):
        for n in (e.get('name'), e.get('name_en')):
            if n:
                part_by_name.setdefault(normalize(n), e)
    # 优先读 bench-data（CI 里刚抓的最新结果），本地没有时退回 assets 里的副本
    bench_data = os.path.join(ROOT, 'bench-data')
    antutu = load_json(os.path.join(bench_data, 'antutu_soc.json'), {}) \
        or load_json(os.path.join(ASSETS, 'bench', 'antutu_soc.json'), {})
    ludashi = load_json(os.path.join(bench_data, 'ludashi.json'), {'soc': []}) \
        or load_json(os.path.join(ASSETS, 'bench', 'ludashi.json'), {'soc': []})
    meta = load_json(os.path.join(ASSETS, 'bench', 'bench_meta.json'), {})
    knowledge = load_json(os.path.join(ASSETS, 'soc_knowledge.json'), {'socs': []})

    # ---- 安兔兔索引：全名与去厂牌名各存一份 ----
    ant_index = {}
    for lv in ('level1', 'level2', 'level3'):
        for r in antutu.get(lv, []):
            ant_index.setdefault(normalize(r['name']), r)
            ant_index.setdefault(strip_vendor(r['name']), r)

    # ---- 人工覆盖：bench_meta.aliases 的 keys 指向本站译名，用它桥接安兔兔 ----
    alias_bridge = {}  # 归一化 key → alias 条目
    for a in meta.get('aliases', []):
        for k in a.get('keys', []):
            alias_bridge[normalize(k)] = a

    lud_index = {}
    for r in ludashi.get('soc', []):
        lud_index.setdefault(normalize(r['name']), r)
        lud_index.setdefault(strip_vendor(r['name']), r)

    # ---- 主表 ----
    socs, exact, loose = [], {}, {}
    assigned, conflicts = {}, []
    for brand_block in chips.get('brands', []):
        brand = brand_block.get('brand', '')
        for c in brand_block.get('chips', []):
            m = c.get('meta') or {}
            codes = split_codenames(m.get('codenames'))
            name, name_en = c.get('name', ''), c.get('name_en', '')

            # 代号缺失时用 soc_name_map 反查（三星 S5E / 华为 KIRIN）
            if not codes:
                for k, v in namemap.items():
                    if v.lower() == name_en.lower() and re.match(
                            r'^(SM\d|MT\d|S5E\d|SDM\d|MSM\d|GS\d{3}|KIRIN\d|V\d{4})', k, re.I):
                        codes.append(k)
            # part_numbers 补的正是极客湾没给号的那一段（三星 Exynos、麒麟、老骁龙）
            pe = part_by_name.get(normalize(name)) or part_by_name.get(normalize(name_en))
            if pe:
                codes = list(dict.fromkeys(codes + pe['codes']))

            # 跑分：先按名直连，再用人工覆盖表桥接
            ant = ant_index.get(normalize(name)) or ant_index.get(strip_vendor(name_en)) \
                or ant_index.get(normalize(name_en))
            if not ant:
                bridge = alias_bridge.get(normalize(name))
                if bridge and bridge.get('antutu_name'):
                    ant = ant_index.get(normalize(bridge['antutu_name']))
            lud = lud_index.get(normalize(name)) or lud_index.get(strip_vendor(name)) \
                or lud_index.get(normalize(name_en)) or lud_index.get(strip_vendor(name_en))

            gpu = (m.get('gpu') or {}).get('name', '')
            soc = {
                'id': make_id(codes[0] if codes else (name_en or name)),
                'codes': codes,
                'names': list(dict.fromkeys(
                    [x for n in (name, name_en) for x in split_alias(n) if x])),
                'name': display_name(name),
                'name_en': name_en,
                'brand': brand,
                'process': m.get('process', ''),
                'cpu': clusters_text(m.get('clusters')),
                'cores': sum((x.get('count') or 0) for x in (m.get('clusters') or [])),
                'max_ghz': max_freq(m.get('clusters')),
                'gpu': gpu,
                'gpu_key': gpu_key(gpu),
                'year': str(m.get('year', '') or ''),
                'tier': m.get('tier', ''),
                'antutu': (ant or {}).get('total_score', ''),
                'antutu_cpu': (ant or {}).get('cpu_score', ''),
                'antutu_gpu': (ant or {}).get('gpu_score', ''),
                'ludashi': (lud or {}).get('score', ''),
                'devices': '',
                'form': 'phone',
                'src': 'socpk',
            }
            socs.append(soc)
            for code in codes:
                assign_code(code, soc['id'], exact, loose, assigned, conflicts)

    # ---- 补齐安兔兔全集 ----
    # 极客湾只收 157 颗且偏新机，骁龙 4xx/6xx、Helio P/G、紫光 T 系列整段不在表里，
    # 而这些正是二手老机与低端机用户手里的芯片。安兔兔从骁龙 425 一直排到 2026 新品，
    # 拿它当覆盖面基线，缺的条目按名字建（有部件号就挂上，没有也先能认出名字）。
    known_names = set()
    known_index = {}
    for s in socs:
        for n in s['names'] + s['codes']:
            known_names.add(normalize(n))
            known_index.setdefault(normalize(n), s)

    specs = load_json(os.path.join(ASSETS, 'bench', 'soc_specs.json'), {}).get('socs', {})
    spec_index = {}
    for k, v in specs.items():
        spec_index.setdefault(normalize(k), v)
        spec_index.setdefault(strip_vendor(k), v)

    l2 = {r.get('rank'): r for r in antutu.get('level2', [])}
    ant_rows = [(r, (l2.get(r.get('rank')) or {}).get('name', ''), 'phone', 'level1')
                for r in antutu.get('level1', [])]
    # level3 是平板/车机/XR/IoT（QCM/QCS/G3x/瑞芯微/全志），同样收，但标 form 便于界面区分
    ant_rows += [(r, '', 'embedded', 'level3') for r in antutu.get('level3', [])]

    def spec_of(name, aliases):
        for a in [name] + aliases:
            sp = spec_index.get(normalize(a)) or spec_index.get(strip_vendor(a))
            if sp:
                return sp
        return {}

    added_ant = 0
    for r, name_en, form, level in ant_rows:
        name = (r.get('name') or '').strip()
        # 安兔兔偶发把分数列当名称抓进来（'25673 分'）：没有字母、或只剩「分」字的一律丢
        if not name or not re.search(r'[A-Za-z]', name) and not re.search(r'[\u4e00-\u9fa5]', name.replace('分', '')):
            continue
        # 先去掉「联发科技 / 高通」前缀再拆斜杠：否则「联发科技 天玑8200/8200 Ultra」
        # 拆出来是带厂牌的一整串，归一化后对不上主表
        base_name = display_name(name)
        aliases = split_alias(base_name)
        # 已经在表里的同芯别名（「天玑8200/8200 Ultra」对「天玑 8200」）补进 names，
        # 否则用户看到的名字对不上、按名字也查不到
        # 用原全名一起查：极客湾写「展锐 T770」、安兔兔写「紫光展锐 T770」，
        # 去掉厂牌后都剩型号，但只拿去厂牌结果查会漏掉带厂牌的那条
        hit = None
        for a in [name] + aliases:
            hit = known_index.get(normalize(a)) or known_index.get(strip_vendor(a))
            if hit:
                break
        if hit:
            for a in aliases:
                if a not in hit['names']:
                    hit['names'].append(a)
            continue
        pe = None
        for a in aliases:
            pe = part_by_name.get(normalize(a)) or part_by_name.get(strip_vendor(a))
            if pe:
                break
        sp = spec_of(name, aliases)
        codes = list(pe['codes']) if pe else []
        main_name = aliases[0]
        # 极客湾名字不带厂牌（天玑 8400），安兔兔带（联发科技 天玑8400-Max）。
        # 界面已有厂商徽章，这里统一去掉首词厂牌，全名保留在 names 里供匹配。
        display = main_name
        gpu = sp.get('gpu', '')
        soc = {
            'id': make_id(codes[0] if codes else display),
            'codes': codes,
            'names': list(dict.fromkeys([name] + aliases)),
            'name': display,
            'name_en': name_en or (pe or {}).get('name_en', ''),
            'brand': (pe or {}).get('brand') or brand_of(name),
            'process': sp.get('process', ''),
            'cpu': sp.get('cpu_summary', ''),
            'cores': sp.get('total_cores', 0),
            'max_ghz': sp.get('max_freq_ghz', 0),
            'gpu': gpu,
            'gpu_key': gpu_key(gpu),
            'year': str(sp.get('year') or (pe or {}).get('year') or ''),
            'tier': '',
            'antutu': r.get('total_score', ''),
            'antutu_cpu': r.get('cpu_score', ''),
            'antutu_gpu': r.get('gpu_score', ''),
            'ludashi': (lud_index.get(normalize(name)) or {}).get('score', ''),
            'devices': '',
            'form': form,
            'src': 'antutu',
        }
        socs.append(soc)
        for code in codes:
            assign_code(code, soc['id'], exact, loose, assigned, conflicts)
        for a in aliases:
            known_names.add(normalize(a))
        added_ant += 1

    # ---- 平台代号（sun/kalama/mt6989）：来自 soc_name_map 的小写键 ----
    platforms = {}
    for k, v in namemap.items():
        if not k.islower() or '_' in k:
            continue
        if k in ('qcom', 'mtk', 'exynos', 'kirin', 'sprd', 'unisoc', 'tensor'):
            continue  # 厂商粒度，不是具体平台
        if k in DEVICE_CODENAMES:
            continue  # 手机代号，不是芯片
        for s in socs:
            if s['name_en'].lower() == v.lower() or s['name'].lower() == v.lower():
                platforms[k] = s['id']
                break

    # ---- 映射表里的料号全部挂上 ----
    # 关键：名字已经在表里时也要把代号挂过去（MT6765V/CA → 曦力 P35）。
    # 旧逻辑遇到同名直接跳过，导致 200 多条联发科料号一条都没进索引，
    # 设备上报 MT6765 时只能落到「未收录」。
    name_to_soc = {}
    for s in socs:
        for n in s['names']:
            name_to_soc.setdefault(normalize(n), s)
            name_to_soc.setdefault(strip_vendor(n), s)

    added_part = 0
    for e in part.get('entries', []):
        names = [n for n in (e.get('name'), e.get('name_en')) if n]
        if any(normalize(n) in name_to_soc for n in names):
            continue
        soc = {
            'id': make_id((e.get('codes') or [e['name']])[0]),
            'codes': list(e.get('codes') or []),
            'names': names, 'name': display_name(e['name']), 'name_en': e.get('name_en', ''),
            'brand': e.get('brand', '') or brand_of(e['name']),
            'process': '', 'cpu': '', 'cores': 0, 'max_ghz': 0,
            'gpu': '', 'gpu_key': '', 'year': str(e.get('year') or ''),
            'tier': '', 'antutu': '', 'antutu_cpu': '', 'antutu_gpu': '',
            'ludashi': '', 'devices': '', 'form': 'phone', 'src': 'code',
        }
        socs.append(soc)
        for n in names:
            name_to_soc[normalize(n)] = soc
            name_to_soc[strip_vendor(n)] = soc
        for code in soc['codes']:
            assign_code(code, soc['id'], exact, loose, assigned, conflicts)
        added_part += 1

    added_code = 0
    for k, v in namemap.items():
        if not re.match(r'^(SM\d{3,5}|MT\d{3,5}|S5E\d{4}|SDM\d{3,4}|MSM\d{3,4}|APQ\d{4}'
                        r'|GS\d{3}|KIRIN\d{3,4}|S5P\d{4})([A-Z0-9/_\-]*)$', k, re.I):
            continue
        s = name_to_soc.get(normalize(v))
        if s is None:
            s = {
                'id': make_id(code_exact(k)),
                'codes': [], 'names': [v], 'name': display_name(v), 'name_en': v,
                'brand': brand_of(v), 'process': '', 'cpu': '', 'cores': 0, 'max_ghz': 0,
                'gpu': '', 'gpu_key': '', 'year': '', 'tier': '', 'antutu': '',
                'antutu_cpu': '', 'antutu_gpu': '', 'ludashi': '', 'devices': '', 'form': 'phone',
            }
            socs.append(s)
            name_to_soc[normalize(v)] = s
            name_to_soc[strip_vendor(v)] = s
        if k not in s['codes'] and assign_code(k, s['id'], exact, loose, assigned, conflicts):
            s['codes'].append(k)
            added_code += 1

    # ---- 常见机型：手写知识库 + EmuReady 设备目录 ----
    # 极客湾与安兔兔都不给机型，只有 soc_knowledge 的 58 条带，覆盖太窄。
    # EmuReady 目录里有 803 台设备的 socName + 机型名，正好补这一段。
    name_idx = {}
    for s in socs:
        for n in [s.get('name', ''), s.get('name_en', '')] + s['names']:
            if n:
                name_idx.setdefault(normalize(n), s)
    emu = load_json(os.path.join(ASSETS, 'emuready-catalog.json'), {})
    by_soc = {}
    for d in emu.get('devices', []):
        soc_name = (d.get('socName') or '').strip()
        model = (d.get('modelName') or '').strip()
        if not soc_name or not model:
            continue
        key = normalize(soc_name)
        if key not in name_idx:
            continue
        by_soc.setdefault(key, []).append((int(d.get('listingsCount') or 0), model))
    emu_devices = 0
    for key, items in by_soc.items():
        s = name_idx[key]
        if s.get('devices'):
            continue
        items.sort(key=lambda x: -x[0])
        names = []
        for _, m in items:
            # 机型名里常带芯片副标题：「KONKR Pocket FIT (G3 Gen 3)」，只留机型
            m = re.sub(r'\s*[（(][^)）]*[)）]\s*$', '', m).strip()
            if m and m not in names:
                names.append(m)
            if len(names) >= 4:
                break
        if names:
            s['devices'] = '、'.join(names)
            emu_devices += 1

    kd = {}
    for e in knowledge.get('socs', []):
        for k in e.get('keys', []):
            kd[normalize(k)] = e
    for s in socs:
        e = kd.get(normalize(s['name'])) or kd.get(normalize(s['name_en']))
        if not e:
            for code in s['codes']:
                e = kd.get(normalize(code))
                if e:
                    break
        if e:
            s['devices'] = e.get('devices', '')
            if not s['process']:
                s['process'] = e.get('process', '')
            if not s['gpu']:
                s['gpu'] = e.get('gpu', '')
                s['gpu_key'] = gpu_key(s['gpu'])

    # ---- 去重（同一 id 可能被代号补齐与极客湾各产生一次） ----
    seen, uniq = {}, []
    for s in socs:
        if s['id'] in seen:
            old = seen[s['id']]
            for f in ('codes', 'names'):
                old[f] = sorted(set(old[f]) | set(s[f]))
            for f in ('process', 'cpu', 'gpu', 'gpu_key', 'antutu', 'ludashi', 'devices', 'year'):
                if not old[f] and s[f]:
                    old[f] = s[f]
            continue
        seen[s['id']] = s
        uniq.append(s)

    # 冲突时 assign_code 拒绝了后到的挂载，但 code 还留在那条记录的 codes 里，
    # 会让「骁龙 8 Gen1」显示成 SM8450（其实归了 8+ Gen1）。这里按最终归属清一遍。
    for s in uniq:
        s['codes'] = [c for c in s['codes'] if assigned.get(code_exact(c)) == s['id']]

    for idx in (exact, loose):
        for k in idx:
            idx[k] = sorted(set(idx[k]))

    # 空字段不写进 JSON：412 条里有三成条目没有工艺/GPU（安兔兔不提供），
    # 全写空串会让文件多 45KB。Kotlin 侧 optString/optInt 都有默认值，缺字段安全。
    keep = {'id', 'name', 'names', 'codes', 'brand', 'src', 'form'}
    slim = [{k: v for k, v in s.items() if k in keep or v not in ('', 0, None, [])} for s in uniq]

    out = {
        'version': '1.1.0',
        'note': '芯片主表。主键是部件号（SM/MT/S5E/SDM/MSM/GS/KIRIN），不是营销名——'
                '同一颗芯片在极客湾/安兔兔/鲁大师写法互不相同（Elite 被安兔兔译作「至尊版」，'
                '安兔兔 Gen5 紧贴而英文站 Gen 5 带空格），设备上报的也只有部件号。'
                '匹配顺序：match.exact 精确 → match.loose 剥后缀 → platforms 平台代号 → GPU+核心数兜底。'
                'loose 命中多条说明该代号对应多个市场名（MT6769=G70/G80/G85），应显示平台族而非猜一个。',
        'generated': __import__('datetime').date.today().isoformat(),
        'socs': slim,
        'match': {'exact': exact, 'loose': loose},
        'platforms': platforms,
        'vendors': part.get('vendor_platforms', {}),
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')

    ById = {s['id']: s for s in uniq}
    coded = sum(1 for s in uniq if s['codes'])
    print(f'芯片 {len(uniq)} 条，有代号 {coded} 条，代号索引 exact={len(exact)} loose={len(loose)}，平台 {len(platforms)} 条')
    print(f'跑分挂接：安兔兔 {sum(1 for s in uniq if s["antutu"])} 条，鲁大师 {sum(1 for s in uniq if s["ludashi"])} 条')
    print(f'新增：安兔兔补 {added_ant} 条，部件号表补 {added_part} 条，映射表挂码 {added_code} 条')
    print(f'常见机型：EmuReady 补 {emu_devices} 条，有 devices 共 {sum(1 for s in uniq if s["devices"])} 条')
    if conflicts:
        print(f'代号冲突 {len(conflicts)} 条（保留先到，未覆盖）：')
        for k, a, b in conflicts[:20]:
            print(f'  {k}: {ById[a]["name"]} ← 丢弃 {ById[b]["name"]}')
    multi = {k: v for k, v in loose.items() if len(v) > 1}
    print(f'一码多名（loose）：{len(multi)} 组，例：' + ', '.join(f'{k}→{len(v)}' for k, v in list(multi.items())[:6]))


if __name__ == '__main__':
    sys.exit(main() or 0)
