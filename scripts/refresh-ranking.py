#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓取模拟器社区排行数据，生成 emulator-ranking.json（全量移植，不截断）。

数据源：
  主：https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json（71 条，含完整 releases/资产直链）
  辅：https://the412banner.github.io/emulator-ranking/data/rankings.json（56 条，补 stars/描述/推送时间）
  GitHub API：仅用于"原站无 releases"的条目补最新版本直链（CI 内用 GITHUB_TOKEN，本地无 token 跳过）

Drivers 分类（驱动仓库）不进模拟器专区。releases/assets 全量保留，不截断。

输出：仓库根目录 emulator-ranking.json（全量远程）+ 精简内置块（离线兜底，元数据+最新版本+最新 3 文件）
"""

import json
import os
import time
import urllib.request

T3_URL = "https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json"
T412_URL = "https://the412banner.github.io/emulator-ranking/data/rankings.json"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "emulator-ranking.json")
GH_TOKEN = os.environ.get("GITHUB_TOKEN", "")

# 官方精选：原版 EmuHub 自带条目，排行里有同 repo 则标记 official，没有则按真实平台分类并入
OFFICIAL = [
    {"id": "winlator", "name": "Winlator", "repo": "brunodev85/winlator",
     "category": "Winlator", "homepage": "https://winlator.org"},
    {"id": "gamehub", "name": "GameHub", "repo": "", "category": "GameHub",
     "homepage": "https://gamehub.com"},
    {"id": "eden", "name": "Eden", "repo": "eden-emulator/Releases",
     "category": "Nintendo Switch Emulator", "homepage": ""},
    {"id": "termux", "name": "Termux", "repo": "termux/termux-app",
     "category": "PC Emulator", "homepage": "https://termux.dev"},
    {"id": "ppsspp", "name": "PPSSPP", "repo": "hrydgard/ppsspp",
     "category": "Emulator PS2", "homepage": "https://www.ppsspp.org"},
    {"id": "retroarch", "name": "RetroArch", "repo": "libretro/RetroArch",
     "category": "All In One", "homepage": "https://www.retroarch.com"},
    {"id": "dolphin", "name": "Dolphin Emulator", "repo": "dolphin-emu/dolphin",
     "category": "Nintendo GameCube / Wii", "homepage": "https://dolphin-emu.org"},
    {"id": "duckstation", "name": "DuckStation", "repo": "stenzek/duckstation",
     "category": "Emulator PS2", "homepage": "https://www.duckstation.org"},
    {"id": "vita3k", "name": "Vita3K", "repo": "Vita3K/Vita3K",
     "category": "PSVITA", "homepage": "https://vita3k.org"},
]

CAT_ZH = {
    "Winlator": "Winlator 专区", "GameHub": "GameHub 专区", "PC Emulator": "PC 模拟器",
    "GameNative": "GameNative 专区", "All In One": "聚合前端", "Nintendo 3DS": "任天堂 3DS",
    "PSVITA": "PS Vita", "Emulator PS2": "PS2 模拟", "Emulator PS3": "PS3 模拟",
    "Emulator PS5": "PS5 模拟", "Wii U Emulator": "Wii U 模拟", "Sega Dreamcast": "Dreamcast 模拟",
    "Nintendo GameCube / Wii": "NGC / Wii 模拟", "Xbox": "Xbox 模拟",
    "Nintendo Switch Emulator": "Switch 模拟", "GAME": "游戏",
}


def fetch(url: str, timeout: int = 60):
    req = urllib.request.Request(url, headers={"User-Agent": "emubox-ci/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def clean(s):
    return (s or "").replace("\r", "").strip()


def gh_api(path: str):
    """GitHub API 调用（CI 内有 token；无 token 返回 None）"""
    if not GH_TOKEN:
        return None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={"Authorization": f"Bearer {GH_TOKEN}", "User-Agent": "emubox-ci/1.0",
                 "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def gh_releases(repo: str, limit: int = 5):
    """GitHub API 补最新 releases（限 5 个）"""
    rels = gh_api(f"/repos/{repo}/releases?per_page={limit}")
    if not rels:
        return []
    out = []
    for rel in rels:
        assets = [{"name": a.get("name", ""), "size": a.get("size", 0),
                   "url": a.get("browser_download_url", "")}
                  for a in rel.get("assets") or []]
        out.append({"tag": rel.get("tag_name", ""), "date": (rel.get("published_at") or "")[:16],
                    "body": clean(rel.get("body"))[:1200], "assets": assets})
    return out


def main():
    t3 = fetch(T3_URL)
    results = t3.get("results", [])

    extra = {}
    try:
        t412 = fetch(T412_URL)
        for r in t412.get("results", []):
            st = r.get("repoStats") or {}
            extra[r.get("repo") or ""] = {
                "stars": st.get("stars", 0),
                "description": clean(st.get("description")),
                "pushed_at": st.get("pushedAt", ""),
            }
    except Exception:
        pass

    official_repos = {o["repo"] for o in OFFICIAL if o["repo"]}
    emus = []
    for r in results:
        cat = r.get("category") or ""
        if cat == "Drivers":
            continue
        repo = r.get("repo") or ""
        ex = extra.get(repo, {})
        rels = []
        for rel in (r.get("releases") or []):
            assets = [{"name": a.get("name", ""), "size": a.get("size", 0), "url": a.get("url", "")}
                      for a in (rel.get("assets") or [])]
            rels.append({"tag": rel.get("tag", ""), "date": (rel.get("date") or "")[:16],
                         "body": clean(rel.get("body"))[:1200], "assets": assets})
        emus.append({
            "id": (repo or r.get("name", "")).replace("/", "-"),
            "name": r.get("name", ""), "repo": repo,
            "repo_url": r.get("repoUrl", ""), "category": cat,
            "official": repo in official_repos,
            "channel": (r.get("channel") or "stable").split("/")[0],
            "downloads": r.get("downloads", 0), "stars": ex.get("stars", 0),
            "description": ex.get("description", ""), "pushed_at": ex.get("pushed_at", ""),
            "homepage": "", "releases": rels,
        })

    # 官方精选：排行里有同 repo 的已标记；没有的并入 + 合理分类 + API 补 releases
    known_repos = {e["repo"] for e in emus if e["repo"]}
    for o in OFFICIAL:
        if o["repo"] and o["repo"] in known_repos:
            continue
        rels = gh_releases(o["repo"]) if o["repo"] else []
        emus.append({
            "id": o["id"], "name": o["name"], "repo": o["repo"],
            "repo_url": f"https://github.com/{o['repo']}" if o["repo"] else "",
            "category": o["category"], "official": True, "channel": "stable",
            "downloads": 0, "stars": 0, "description": "", "pushed_at": "",
            "homepage": o["homepage"], "releases": rels,
        })

    # 原站有但 releases 为空（eden/citron/sumi/kenji 等）→ API 补
    for e in emus:
        if not e["releases"] and e["repo"]:
            e["releases"] = gh_releases(e["repo"])
            print(f"  API 补充: {e['name']} -> {len(e['releases'])} releases")

    emus.sort(key=lambda e: (-(1 if e["official"] else 0), -(e.get("downloads") or 0)))
    cats = {}
    for e in emus:
        c = e["category"]
        cats[c] = cats.get(c, 0) + 1
    doc = {
        "updated_at": t3.get("updatedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "source": "https://t3st31.github.io/Ranking-Emulators-Download/ + the412banner/emulator-ranking",
        "categories": [{"key": c, "label": CAT_ZH.get(c, c), "count": n}
                       for c, n in sorted(cats.items(), key=lambda x: -x[1])],
        "emulators": emus,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    total_rel = sum(len(e["releases"]) for e in emus)
    total_as = sum(len(a) for e in emus for r in e["releases"] for a in [r["assets"]])
    print(f"已更新：{len(emus)} 条 / {len(cats)} 分类 / {total_rel} releases / {total_as} 资产 / {os.path.getsize(OUT)/1024/1024:.2f} MB")
    print("分类:", cats)


if __name__ == "__main__":
    main()
