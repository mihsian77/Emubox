#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器社区排行全量合并脚本 v4

主源 the412banner/emulator-ranking（字段最全：logo/homepage/stars/description/pushedAt），
t3st31/Ranking-Emulators-Download 补其独有条目。两个站均为静态 GitHub Pages JSON，每日自动更新。
排除 Drivers 分类（驱动走驱动专区，不混入模拟器）。
输出：仓库根目录 emulator-ranking.json（全量 releases/assets）+ scripts/emu_builtin_block.txt（内置块）。
"""
import json, os, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "emulator-ranking.json")
BUILTIN = os.path.join(ROOT, "scripts", "emu_builtin_block.txt")

T412 = "https://the412banner.github.io/emulator-ranking/data/rankings.json"
T331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json"
LOGO_T412 = "https://the412banner.github.io/emulator-ranking/logos/"
LOGO_T331 = "https://t3st31.github.io/Ranking-Emulators-Download/logos/"

CAT_ZH = {
    "Winlator": "Winlator 专区", "GameHub": "GameHub 专区", "PC Emulator": "PC 模拟器",
    "GameNative": "GameNative 专区", "All In One": "聚合前端", "Nintendo 3DS": "任天堂 3DS",
    "PSVITA": "PS Vita", "Emulator PS2": "PS2 模拟", "Emulator PS3": "PS3 模拟",
    "Emulator PS5": "PS5 模拟", "Wii U Emulator": "Wii U 模拟", "Sega Dreamcast": "Dreamcast 模拟",
    "Nintendo GameCube / Wii": "NGC-Wii 模拟", "Xbox": "Xbox 模拟",
    "Nintendo Switch Emulator": "Switch 模拟", "GAME": "游戏",
}

OFFICIAL = {  # 官方/主渠道条目（真分类，避免乱分）
    "brunodev85/winlator": ("Winlator", True),
    "gamehub-studio/gamehub": ("GameHub", True),
    "eden-emulator/releases": ("Nintendo Switch Emulator", True),
    "termux/termux-app": ("PC Emulator", True),
    "hrydgard/ppsspp": ("Emulator PS2", True),
    "stenzek/duckstation": ("Emulator PS2", True),
    "libretro/retroarch": ("All In One", True),
    "dolphin-emu/dolphin": ("Nintendo GameCube / Wii", True),
    "Vita3K/Vita3K": ("PSVITA", True),
}


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 emubox-refresh"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def norm_releases(rels):
    out = []
    for r in rels or []:
        assets = [{
            "name": a.get("name", ""),
            "size": int(a.get("size") or 0),
            "url": a.get("url", ""),
        } for a in (r.get("assets") or []) if a.get("name")]
        body = (r.get("body") or "").replace("\r", "")
        out.append({
            "tag": r.get("tag", ""),
            "name": r.get("name", ""),
            "date": r.get("date", ""),
            "body": body[:1200],
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return out


def gh_releases(repo):
    """CI 内用 GITHUB_TOKEN 给原站无 releases 的条目补最新版本（本地无 token 返回空）。"""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        return []
    url = f"https://api.github.com/repos/{repo}/releases?per_page=30"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "User-Agent": "emubox"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            rels = json.load(r)
    except Exception as e:
        print(f"  API 失败 {repo}: {e}")
        return []
    out = []
    for r in rels:
        assets = [{"name": a["name"], "size": int(a.get("size") or 0), "url": a.get("browser_download_url", "")}
                  for a in (r.get("assets") or []) if a.get("name")]
        out.append({
            "tag": r.get("tag_name", ""),
            "name": r.get("name", ""),
            "date": r.get("published_at", ""),
            "body": (r.get("body") or "").replace("\r", "")[:1200],
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return out


def main():
    t412 = fetch(T412)
    t331 = fetch(T331)
    t412r = t412.get("results") or []
    t331r = t331.get("results") or []

    by_repo = {}
    order = []

    def add(e, logo_base):
        repo = e.get("repo") or ""
        if not repo or e.get("category") == "Drivers":
            return
        # 工具/聚合类条目排除：BannerHub/Bannerlator/WinNative（非模拟器本体）
        _n = (e.get("name") or "").lower()
        if any(k in _n for k in ("banner", "bannerlator", "winnative")):
            return
        key = repo.lower()
        if key in by_repo:
            return
        cat, off = OFFICIAL.get(repo, (e.get("category", ""), False))
        logo = e.get("logo") or ""
        rels = norm_releases(e.get("releases"))
        if not rels:
            rels = gh_releases(repo)
        by_repo[key] = {
            "id": key.replace("/", "-"),
            "name": e.get("name", ""),
            "repo": repo,
            "repo_url": e.get("repoUrl") or f"https://github.com/{repo}",
            "category": cat,
            "official": off,
            "channel": e.get("channel", "stable"),
            "downloads": int(e.get("downloads") or 0),
            "stars": 0,
            "description": "",
            "pushed_at": "",
            "homepage": "",
            "logoUrl": (logo_base + logo) if logo else "",
            "releases": rels,
        }
        order.append(key)

    # 主源 the412banner（字段全）
    for e in t412r:
        add(e, LOGO_T412)
    # t331 补独有（repo 未收录的条目）
    for e in t331r:
        repo = e.get("repo") or ""
        if repo.lower() in by_repo:
            continue
        add(e, LOGO_T331)
    # 合并后补 t412 元数据（stars/description/pushed_at/homepage）
    stats = {e["repo"].lower(): (e.get("repoStats") or {}) for e in t412r}
    for key in order:
        s = stats.get(key, {})
        e = by_repo[key]
        e["stars"] = int(s.get("stars") or 0)
        e["description"] = s.get("description") or ""
        e["pushed_at"] = s.get("pushedAt") or ""
        e["homepage"] = s.get("homepage") or ""

    emus = [by_repo[k] for k in order]
    emus.sort(key=lambda e: (-(1 if e["official"] else 0), -(e.get("downloads") or 0)))
    cats = {}
    for e in emus:
        cats[e["category"]] = cats.get(e["category"], 0) + 1
    doc = {
        "updated_at": t412.get("updatedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "source": "the412banner/emulator-ranking + t3st31/Ranking-Emulators-Download（每日自动刷新）",
        "categories": [{"key": c, "label": CAT_ZH.get(c, c), "count": n}
                       for c, n in sorted(cats.items(), key=lambda x: -x[1])],
        "emulators": emus,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    total_rel = sum(len(e["releases"]) for e in emus)
    total_as = sum(len(a) for e in emus for r in e["releases"] for a in r["assets"])
    print(f"已更新：{len(emus)} 条 / {len(cats)} 分类 / {total_rel} releases / {total_as} 资产 / {os.path.getsize(OUT)/1024/1024:.2f} MB")
    print("分类:", cats)

    # 内置块（离线兜底：元数据+最新版本+最新文件；全量 releases 走远程拉取）
    def ks(v: str) -> str:
        """Kotlin 字符串字面量转义（换行/引号/反斜杠）"""
        return (v or "").replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "")

    block_lines = []
    for e in emus:
        rel0 = (e["releases"] or [{}])[0]
        asset0 = (rel0.get("assets") or [{}])[0]
        block_lines.append(
            "EmulatorEntry("
            f"id = \"{e['id']}\", name = \"{ks(e['name'])}\", category = \"{ks(e['category'])}\", "
            f"description = \"{ks(e['description'][:160])}\", owner = \"{ks((e['repo'].split('/') + [''])[0])}\", "
            f"repo = \"{ks(e['repo'].split('/')[-1] if '/' in e['repo'] else e['repo'])}\", "
            f"homepage = \"{ks(e['homepage'])}\", repoUrl = \"{ks(e['repo_url'])}\", official = {str(e['official']).lower()}, "
            f"channel = \"{ks(e['channel'])}\", downloads = {e['downloads']}, stars = {e['stars']}, "
            f"pushedAt = \"{ks(e['pushed_at'])}\", latestTag = \"{ks(rel0.get('tag', ''))}\", "
            f"latestDate = \"{ks(rel0.get('date', ''))}\", latestBody = \"{ks(rel0.get('body', '')[:200])}\", "
            f"logoUrl = \"{ks(e['logoUrl'])}\", "
            f"latestApkName = \"{ks(asset0.get('name', ''))}\", latestApkUrl = \"{ks(asset0.get('url', ''))}\", "
            f"latestApkSize = {asset0.get('size', 0)}"
            "),"
        )
    with open(BUILTIN, "w", encoding="utf-8") as f:
        f.write("\n".join(block_lines))
    print(f"内置块：{len(block_lines)} 条 -> {BUILTIN}")


if __name__ == "__main__":
    main()
