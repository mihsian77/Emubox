#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生态发现：从 GitHub 搜索 → 打标签 → 评分 → 产出候选池（不直接写 registry）。

设计原则
--------
1. **发现自动化，收录人工化**。本脚本只产出 `候选池-YYYYMMDD.json/.md`，
   绝不改动 emulator-registry.json。写库仍走 PR。
2. **硬门槛优先于分数**。archived / 无 release / 两年没动，任一命中直接降为
   `review`，避免"高 star 但已死"的项目被算成高分（Sudachi、Citra 的教训）。
3. **去重按 owner/repo**，同时比对 registry 的 repo 字段与 id，防止改名项目重复入池。

用法
----
    python3 scripts/discover_candidates.py              # 默认 8 组关键词
    python3 scripts/discover_candidates.py --limit 50   # 每组取更多
    python3 scripts/discover_candidates.py --offline    # 只重算、不请求 API

输出
----
    /workspace/生态发现/候选池-YYYYMMDD.json
    /workspace/生态发现/候选池-YYYYMMDD.md
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.environ.get("EMUBOX_OUT", "/workspace/生态发现")
TOKEN_FILE = "/root/.codebuddy/.secrets/gh-token"

# 关键词矩阵：每组带一个默认 tier 标签，用于后续归类和人工复核时排序
QUERIES = [
    # (搜索语句, 领域标签)
    # 注意：GitHub 的 OR 是全文布尔展开，'galgame OR "visual novel" android'
    # 会被拆成三个词分别命中，把 v2rayNG / Joplin 这类高星项目捞进来。
    # 因此一律用「单一领域词 + android」，靠下面的 DOMAIN_LEXICON 二次校验。
    ('winlator in:name,description', "winlator"),
    ('winlator android in:name,description', "winlator"),
    ('box64 android in:name,description', "winlator"),
    ('wine android emulator in:name,description', "winlator"),
    ('"switch 2" emulator android in:name,description', "switch"),
    ('"xbox 360" emulator android in:name,description', "xbox"),
    ('xenia emulator in:name,description', "xbox"),
    ('turnip adrenotools in:name,description', "driver"),
    ('panvk in:name,description', "driver"),
    ('galgame android in:name,description', "galgame"),
    ('kirikiri in:name,description', "galgame"),
    ('visual novel emulator android in:name,description', "galgame"),
    ('recomp android in:name,description', "port"),
    # ── 中文圈 ──
    # GitHub 中文全文检索噪声极大（"模拟器"会捞到桌宠、量化、修仙模拟器），
    # 所以限制在 name,description，且必须过 DOMAIN_LEXICON 的英文词闸门。
    # 目的不是让中文项目直接入库，而是让它们进候选池、被人看见。
    ('模拟器 in:name,description', "cn"),
    ('galgame in:name,description', "cn"),
    ('视觉小说 in:name,description', "cn"),
    ('winlator in:name,description', "cn"),
]

# 新项目扫描：按「最近更新」排序 + 建仓时间过滤。
# 不带 stars 门槛，因为新项目天然星少；靠后面的 in_domain 和人工两道关。
# 这是为了补上「按 star 排序 = 只收大项目」的结构性缺陷。
NEW_QUERIES = [
    ('emulator android in:name,description created:>2026-05-01', "android"),
    ('winlator in:name,description created:>2026-01-01', "winlator"),
    ('galgame in:name,description created:>2026-01-01', "galgame"),
    ('"visual novel" runtime in:name,description created:>2026-01-01', "galgame"),
    ('"native port" android in:name,description created:>2026-01-01', "port"),
    ('recomp in:name,description created:>2025-06-01', "port"),
    ('模拟器 android in:name,description created:>2026-01-01', "cn"),
]

# 领域词表：仓库的 name / topics / description 必须命中至少一个，
# 否则判定为「搜索噪声」直接丢弃。这是防止高星无关项目靠 star 拿高分的闸门。
DOMAIN_LEXICON = {
    "winlator", "winlator", "wine", "box64", "box86", "exagear", "proot",
    "emulator", "emulation", "emulate", "retroarch", "libretro",
    "switch", "yuzu", "ryujinx", "eden", "sudachi", "suyu", "horizon",
    "xbox", "xenia", "x360", "xendroid", "xenra",
    "ps1", "ps2", "ps3", "ps4", "ps5", "playstation", "pcsx2", "rpcs3",
    "gamecube", "wii", "dolphin", "3ds", "citra", "azahar", "nds", "desmume",
    "melonds", "gba", "n64", "dreamcast", "saturn", "psp", "ppsspp", "vita",
    "turnip", "panvk", "adrenotools", "freedreno", "mesa", "vulkan driver",
    # 中文领域词：只在 name/desc 命中时放行，避免全库误判
    # "汉化"/"中文版" 太宽（任何汉化组、任何中文软件都会命中），不加
    "模拟器", "视觉小说", "金手指", "转译",
    "galgame", "visual novel", "kirikiri", "krkr", "onscripter", "tyrano",
    "artemis", "siglus", "renpy", "rpg maker",
    "recomp", "recompiled", "decomp", "native port", "port to android",
}

NAME_BLOCK = {"mcp", "awesome", "cli", "sdk", "template"}

# 明确的非目标：驱动/工具类平台项目，即使命中 emulator 词也排除
BLOCK_TERMS = {
    "v2ray", "xray", "proxy", "vpn", "magisk", "debloat", "note taking",
    "note-taking", "chat assistant", "ui toolkit", "framework for",
    "screen mirror", "screen copy", "scrcpy", "kms emulator", "license server",
    "dex dump", "unpack", "awesome list", "awesome-", "curated list",
}

HARD_STALE_DAYS = 730      # 两年未提交 → 不算 active
SOFT_STALE_DAYS = 365


def in_domain(item):
    """判断仓库是否真属于模拟器/兼容层生态。返回 (bool, 命中词或排除原因)。"""
    name = (item.get("name") or "").lower()
    desc = (item.get("description") or "").lower()
    topics = [t.lower() for t in (item.get("topics") or [])]
    blob = " ".join([name, desc, *topics])

    # 名字里出现这些词，基本可以确定不是模拟器（mcp 指 AI agent 工具链，
    # 但 unidbg 这类项目的 topics 里也可能挂 mcp，所以只认名字）
    for b in NAME_BLOCK:
        if b in name:
            return False, f"名称含排除词 {b}"
    for b in BLOCK_TERMS:
        if b in blob:
            return False, f"命中排除词 {b}"

    # topics 命中最可信；name 次之；description 单独命中不算（噪声太多）
    for t in topics:
        if any(t == w or w in t for w in DOMAIN_LEXICON):
            return True, f"topic:{t}"
    # 用词边界匹配：name 子串会把 xenray（Xray 客户端）误判成 xenra，
    # 也会把 scrcpy 之类的缩写蹭进来。\b 要求前后是分词边界。
    def hit(text, w):
        return re.search(r"(?<![a-z0-9])" + re.escape(w) + r"(?![a-z0-9])", text) is not None

    for w in DOMAIN_LEXICON:
        if hit(name, w):
            return True, f"name:{w}"
    for w in DOMAIN_LEXICON:
        if hit(desc, w):
            return True, f"desc:{w}"
    if not desc.strip() and not topics:
        return None, "信息不足（无描述/话题）"   # 不判死，交人工
    return False, "无领域词"


def load_token():
    # Actions 里没有 TOKEN_FILE，靠 GH_TOKEN；本地反过来，优先文件
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"].strip()
    if os.environ.get("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"].strip()
    return open(TOKEN_FILE, encoding="utf-8").read().strip()


def gh(url, token):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "emubox-discover",
        **({"Authorization": f"Bearer {token}"} if token else {}),
    })
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r)


def search(q, token, per_page=30, sort="stars"):
    """sort=stars 只能捞到大项目，新项目会被巨头挤掉（Aether 200★ 在
    'galgame android' 里排不进前 6）。新项目扫描必须用 sort=updated。"""
    url = ("https://api.github.com/search/repositories?q="
           + urllib.parse.quote(q) + f"&sort={sort}&order=desc&per_page={per_page}")
    try:
        return gh(url, token).get("items", [])
    except Exception as exc:  # noqa: BLE001
        print(f"  ! 搜索失败 {q}: {exc}", file=sys.stderr)
        return []


def days_since(iso):
    if not iso:
        return 99999
    try:
        t = dt.datetime.strptime(iso, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
    except ValueError:
        return 99999
    return (dt.datetime.now(dt.timezone.utc) - t).days


def has_apk(releases):
    for r in releases or []:
        for a in r.get("assets", []):
            n = (a.get("name") or "").lower()
            if n.endswith(".apk") or n.endswith(".apks") or n.endswith(".xapk"):
                return True, a.get("name", ""), a.get("size", 0)
    return False, "", 0


def score(item, releases, today_days):
    """返回 (score, 明细dict, 硬门槛问题list)"""
    d = {}
    gates = []

    pushed = days_since(item.get("pushed_at"))
    if item.get("archived"):
        gates.append("仓库已归档")
    if pushed > HARD_STALE_DAYS:
        gates.append(f"{pushed} 天未提交")

    # A 活跃度 30
    if pushed <= 7:
        d["活跃度"] = 30
    elif pushed <= 30:
        d["活跃度"] = 25
    elif pushed <= 90:
        d["活跃度"] = 20
    elif pushed <= 180:
        d["活跃度"] = 14
    elif pushed <= SOFT_STALE_DAYS:
        d["活跃度"] = 8
    else:
        d["活跃度"] = 0

    # B 社区 25
    s = item.get("stargazers_count") or 0
    if s >= 1000:
        d["社区度"] = 25
    elif s >= 200:
        d["社区度"] = 22
    elif s >= 50:
        d["社区度"] = 18
    elif s >= 10:
        d["社区度"] = 12
    elif s >= 1:
        d["社区度"] = 5
    else:
        d["社区度"] = 0
    if (item.get("forks_count") or 0) >= 5:
        d["社区度"] = min(25, d["社区度"] + 2)

    # C 可分发性 25
    apk, apk_name, apk_size = has_apk(releases)
    if apk:
        d["可分发性"] = 25
    elif releases:
        d["可分发性"] = 15
    else:
        d["可分发性"] = 5
        gates.append("无 release")

    # D 可核验 12
    d["可核验"] = 0
    if item.get("license"):
        d["可核验"] += 4
    if item.get("homepage"):
        d["可核验"] += 4
    if (item.get("description") or "").strip():
        d["可核验"] += 4

    # E 新项目加成 8（生态发现要抓早期，但只在硬门槛通过时给）
    age = days_since(item.get("created_at"))
    d["新锐度"] = 8 if age <= 90 else (4 if age <= 365 else 0)
    if gates:
        d["新锐度"] = 0

    total = sum(d.values())
    meta = {"apk": apk, "apk_name": apk_name, "apk_size": apk_size,
            "pushed_days": pushed, "age_days": age}
    return total, d, gates, meta


def verdict(total, gates):
    if gates:
        return "review" if total >= 40 else "ignore"
    if total >= 70:
        return "suggest"      # 建议收录，仍需人工确认领域归属
    if total >= 45:
        return "review"
    return "ignore"


def load_registry():
    """同时读 emulator-registry 与 driver-registry，否则 K11MCH1 这类
    已在驱动库的项目会被重复报成「建议收录」。"""
    known = set()
    for fname, key in (("emulator-registry.json", "emulators"),
                       ("driver-registry.json", "repositories")):
        p = os.path.join(ROOT, fname)
        if not os.path.exists(p):
            continue
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        for e in data.get(key, []):
            r = e.get("repo") or ""
            o = e.get("owner") or ""
            if r:
                known.add(r.lower())
                if o:
                    known.add(f"{o}/{r}".lower())
            if e.get("id"):
                known.add(e["id"].lower())
    return known


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()

    token = load_token()
    known = load_registry()
    seen, pool = {}, {}

    if not args.offline:
        per = max(10, args.limit // len(QUERIES) + 5)
        # 新项目扫描每条多取一些：它们星数低，排序后容易被挤到后面
        per_new = max(15, args.limit // 2)
        jobs = [(q, t, "stars", per) for q, t in QUERIES] + \
               [(q, t, "updated", per_new) for q, t in NEW_QUERIES]
        for q, tag, srt, n in jobs:
            print(f"[搜索{'/新' if srt == 'updated' else ''}] {q}", file=sys.stderr)
            for it in search(q, token, n, sort=srt):
                fn = it["full_name"]
                if fn.lower() in seen:
                    pool[fn]["tags"].add(tag)
                    continue
                seen[fn.lower()] = True
                pool[fn] = {"item": it, "tags": {tag}}
            time.sleep(2.2)   # 限速放在「每次搜索」之间；原先放在结果循环内，
                              # 一条 7 个结果会 sleep 15 秒，整轮跑不完被 timeout 杀掉
        cache = os.path.join(OUT_DIR, "raw-cache.json")
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(cache, "w", encoding="utf-8") as f:
            json.dump({k: {"item": v["item"], "tags": sorted(v["tags"])}
                       for k, v in pool.items()}, f, ensure_ascii=False)
    else:
        cache = os.path.join(OUT_DIR, "raw-cache.json")
        with open(cache, encoding="utf-8") as f:
            raw = json.load(f)
        pool = {k: {"item": v["item"], "tags": set(v["tags"])} for k, v in raw.items()}

    rows = []
    dropped = []
    weak = []
    # 先过领域闸门，再并发拉 release；串行 150 个仓库要 5 分钟以上，
    # 会被外层 timeout 杀掉，只写缓存不写产物（已踩过两次）。
    alive = []
    for fn, v in pool.items():
        ok, why = in_domain(v["item"])
        if ok is False:
            dropped.append((fn, why))
            continue
        if ok is None:
            weak.append((fn, why))
        alive.append((fn, v))

    from concurrent.futures import ThreadPoolExecutor

    def fetch_rel(fn):
        try:
            return fn, gh(f"https://api.github.com/repos/{fn}/releases?per_page=5", token)
        except Exception:
            return fn, []

    with ThreadPoolExecutor(max_workers=8) as ex:
        relmap = dict(ex.map(fetch_rel, [fn for fn, _ in alive]))

    for fn, v in alive:
        it = v["item"]
        rel = relmap.get(fn, [])
        total, detail, gates, meta = score(it, rel, 0)
        rows.append({
            "full_name": fn,
            "name": it.get("name"),
            "owner": (it.get("owner") or {}).get("login"),
            "repo": it.get("name"),
            "description": it.get("description") or "",
            "homepage": it.get("homepage") or "",
            "stars": it.get("stargazers_count") or 0,
            "forks": it.get("forks_count") or 0,
            "language": it.get("language"),
            "pushed_at": it.get("pushed_at"),
            "created_at": it.get("created_at"),
            "archived": bool(it.get("archived")),
            "license": ((it.get("license") or {}).get("spdx_id")),
            "tags": sorted(v["tags"]),
            "score": total,
            "score_detail": detail,
            "gates": gates,
            "verdict": verdict(total, gates),
            "already_in_registry": fn.lower() in known,
            **meta,
        })

    rows.sort(key=lambda r: (-r["score"], -r["stars"]))
    today = dt.date.today().strftime("%Y%m%d")
    os.makedirs(OUT_DIR, exist_ok=True)
    jp = os.path.join(OUT_DIR, f"候选池-{today}.json")
    with open(jp, "w", encoding="utf-8") as f:
        json.dump({"generated": dt.datetime.now().isoformat(timespec="seconds"),
                   "total": len(rows), "rows": rows}, f, ensure_ascii=False, indent=1)

    new = [r for r in rows if not r["already_in_registry"]]
    buckets = {"suggest": [], "review": [], "ignore": []}
    for r in new:
        buckets[r["verdict"]].append(r)

    md = [f"# 生态候选池 · {today}", "",
          f"> 搜索命中 {len(rows) + len(dropped)} 个仓库，领域校验剔除 {len(dropped)} 个；"
          f"余 {len(rows)} 个，库内已有 {len(rows) - len(new)} 个。"
          f"**本文件不写库**，只是候选清单。", "",
          "## 建议收录（分数 ≥70 且过硬门槛）", "",
          "| 项目 | ★ | 分 | 标签 | 最近提交 | APK | 说明 |",
          "|---|--:|--:|---|---|---|---|"]
    for r in buckets["suggest"][:40]:
        apk = f"{r['apk_size']/1048576:.0f} MB" if r["apk"] else "—"
        md.append(f"| [{r['name']}](https://github.com/{r['full_name']}) | {r['stars']} | "
                  f"{r['score']} | {','.join(r['tags'])} | {r['pushed_days']} 天前 | "
                  f"{apk} | {(r['description'] or '')[:48]} |")
    md += ["", "## 待人工判断", "",
           "| 项目 | ★ | 分 | 卡在哪 | 说明 |", "|---|--:|--:|---|---|"]
    for r in buckets["review"][:40]:
        md.append(f"| [{r['name']}](https://github.com/{r['full_name']}) | {r['stars']} | "
                  f"{r['score']} | {'；'.join(r['gates']) or '分数偏低'} | "
                  f"{(r['description'] or '')[:44]} |")
    md += ["", f"忽略 {len(buckets['ignore'])} 个（分数低）。", "",
           "## 被领域校验剔除（防误收）", "",
           "| 仓库 | 原因 |", "|---|---|"]
    for fn, why in dropped[:25]:
        md.append(f"| {fn} | {why} |")
    if weak:
        md += ["", "## 信息不足，需人工看一眼", "", "| 仓库 | 原因 |", "|---|---|"]
        for fn, why in weak[:20]:
            md.append(f"| {fn} | {why} |")
    mp = os.path.join(OUT_DIR, f"候选池-{today}.md")
    with open(mp, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    print(f"建议 {len(buckets['suggest'])} / 待判 {len(buckets['review'])} / "
          f"忽略 {len(buckets['ignore'])} / 已收录 {len(rows)-len(new)}")
    print(jp)
    print(mp)


if __name__ == "__main__":
    main()
