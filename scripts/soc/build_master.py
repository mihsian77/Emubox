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
    s = re.sub(r'[\s\-_+*]', '', s)
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
    """代号优先做 id；纯中文名（如「麒麟 8000」没有英文名）落到哈希，避免 id 为空。"""
    slug = re.sub(r'[^a-z0-9]+', '-', (base or '').lower()).strip('-')
    return slug or 'soc-' + hashlib.md5((base or '').encode('utf-8')).hexdigest()[:8]


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
                'names': [n for n in (name, name_en) if n],
                'name': name,
                'name_en': name_en,
                'brand': brand,
                'process': m.get('process', ''),
                'cpu': clusters_text(m.get('clusters')),
                'cores': sum((x.get('count') or 0) for x in (m.get('clusters') or [])),
                'max_ghz': max_freq(m.get('clusters')),
                'gpu': gpu,
                'gpu_key': gpu_key(gpu),
                'year': m.get('year', ''),
                'tier': m.get('tier', ''),
                'antutu': (ant or {}).get('total_score', ''),
                'antutu_cpu': (ant or {}).get('cpu_score', ''),
                'antutu_gpu': (ant or {}).get('gpu_score', ''),
                'ludashi': (lud or {}).get('score', ''),
                'devices': '',
            }
            socs.append(soc)
            for code in codes:
                exact.setdefault(code_exact(code), []).append(soc['id'])
                loose.setdefault(code_loose(code), []).append(soc['id'])

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

    # ---- 补充只有代号没有市场名的平台（极客湾未收录） ----
    known_names = {s['name_en'].lower() for s in socs} | {s['name'].lower() for s in socs}
    for k, v in namemap.items():
        if not re.match(r'^(SM\d{4}|MT\d{4}|S5E\d{4}|SDM\d{3,4}|MSM\d{3,4}|GS\d{3}|KIRIN\d{3,4}|V\d{4})$', k, re.I):
            continue
        if v.lower() in known_names:
            continue
        socs.append({
            'id': re.sub(r'[^a-z0-9]+', '-', k.lower()).strip('-'),
            'codes': [k], 'names': [v], 'name': v, 'name_en': v, 'brand': '',
            'process': '', 'cpu': '', 'cores': 0, 'max_ghz': 0, 'gpu': '', 'gpu_key': '',
            'year': '', 'tier': '', 'antutu': '', 'antutu_cpu': '', 'antutu_gpu': '',
            'ludashi': '', 'devices': '',
        })
        known_names.add(v.lower())
        exact.setdefault(code_exact(k), []).append(socs[-1]['id'])
        loose.setdefault(code_loose(k), []).append(socs[-1]['id'])

    # ---- 常见机型：从 soc_knowledge 合并（接口没有这个字段） ----
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

    for idx in (exact, loose):
        for k in idx:
            idx[k] = sorted(set(idx[k]))

    out = {
        'version': '1.0.0',
        'note': '芯片主表。主键是部件号（SM/MT/S5E/SDM/MSM/GS/KIRIN），不是营销名——'
                '同一颗芯片在极客湾/安兔兔/鲁大师写法互不相同（Elite 被安兔兔译作「至尊版」，'
                '安兔兔 Gen5 紧贴而英文站 Gen 5 带空格），设备上报的也只有部件号。'
                '匹配顺序：match.exact 精确 → match.loose 剥后缀 → platforms 平台代号 → GPU+核心数兜底。'
                'loose 命中多条说明该代号对应多个市场名（MT6769=G70/G80/G85），应显示平台族而非猜一个。',
        'generated': __import__('datetime').date.today().isoformat(),
        'socs': uniq,
        'match': {'exact': exact, 'loose': loose},
        'platforms': platforms,
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')

    coded = sum(1 for s in uniq if s['codes'])
    print(f'芯片 {len(uniq)} 条，有代号 {coded} 条，代号索引 exact={len(exact)} loose={len(loose)}，平台 {len(platforms)} 条')
    print(f'跑分挂接：安兔兔 {sum(1 for s in uniq if s["antutu"])} 条，鲁大师 {sum(1 for s in uniq if s["ludashi"])} 条')
    multi = {k: v for k, v in loose.items() if len(v) > 1}
    print(f'一码多名（loose）：{len(multi)} 组，例：' + ', '.join(f'{k}→{len(v)}' for k, v in list(multi.items())[:6]))


if __name__ == '__main__':
    sys.exit(main() or 0)
