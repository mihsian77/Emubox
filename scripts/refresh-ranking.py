#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓取模拟器社区排行数据，生成 emulator-ranking.json（全量移植）。

数据源：
  主：https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json（71 条，含完整 releases/资产/来源渠道）
  辅：https://the412banner.github.io/emulator-ranking/data/rankings.json（56 条，补 stars/描述/推送时间）
两站均为每日自动更新的社区排行。Drivers 分类（驱动仓库）不进模拟器专区。

输出：仓库根目录 emulator-ranking.json（保留原站分类，releases 含 tag/date/body/assets 直链）
"""

import json
import os
import time
import urllib.request

T3_URL = "https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json"
T412_URL = "https://the412banner.github.io/emulator-ranking/data/rankings.json"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "emulator-ranking.json")

# 官方精选：原版 EmuHub 自带条目（排行里有同 repo 则标记 official，没有则并入）
OFFICIAL = [
    {"id": "winlator", "name": "Winlator", "repo": "brunodev85/winlator",
     "homepage": "https://winlator.org"},
    {"id": "gamehub", "name": "GameHub", "repo": "", "homepage": "https://gamehub.com"},
    {"id": "eden", "name": "Eden", "repo": "", "homepage": ""},
    {"id": "termux", "name": "Termux", "repo": "termux/termux-app",
     "homepage": "https://termux.dev"},
    {"id": "ppsspp", "name": "PPSSPP", "repo": "hrydgard/ppsspp",
     "homepage": "https://www.ppsspp.org"},
    {"id": "retroarch", "name": "RetroArch", "repo": "libretro/RetroArch",
     "homepage": "https://www.retroarch.com"},
    {"id": "dolphin", "name": "Dolphin Emulator", "repo": "dolphin-emu/dolphin",
     "homepage": "https://dolphin-emu.org"},
    {"id": "duckstation", "name": "DuckStation", "repo": "stenzek/duckstation",
     "homepage": "https://www.duckstation.org"},
    {"id": "vita3k", "name": "Vita3K", "repo": "Vita3K/Vita3K",
     "homepage": "https://vita3k.org"},
]


def fetch(url: str, timeout: int = 40):
    req = urllib.request.Request(url, headers={"User-Agent": "emubox-ci/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def clean(s):
    return (s or "").replace("\r", "").strip()


def main():
    t3 = fetch(T3_URL)
    results = t3.get("results", [])
    # 补 stars/描述/推送时间（the412banner 有 repoStats）
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

    emus = []
    for r in results:
        cat = r.get("category") or ""
        if cat == "Drivers":
            continue
        repo = r.get("repo") or ""
        ex = extra.get(repo, {})
        rels = []
        for rel in (r.get("releases") or [])[:8]:
            assets = []
            for a in (rel.get("assets") or [])[:6]:
                size = a.get("size")
                try:
                    size = int(size) if str(size).isdigit() else 0
                except Exception:
                    size = 0
                assets.append({
                    "name": a.get("name", ""),
                    "size": size,
                    "url": a.get("url", ""),
                })
            rels.append({
                "tag": rel.get("tag", ""),
                "date": (rel.get("date") or "")[:16],
                "body": clean(rel.get("body"))[:1200],
                "assets": assets,
            })
        emus.append({
            "id": (repo or r.get("name", "")).replace("/", "-"),
            "name": r.get("name", ""),
            "repo": repo,
            "repo_url": r.get("repoUrl", ""),
            "category": cat,
            "official": repo in {o["repo"] for o in OFFICIAL if o["repo"]},
            "channel": (r.get("channel") or "stable").split("/")[0],
            "source_kind": (r.get("sourceKind") or "community").split("/")[0],
            "downloads": r.get("downloads", 0),
            "stars": ex.get("stars", 0),
            "description": ex.get("description", ""),
            "pushed_at": ex.get("pushed_at", ""),
            "homepage": "",
            "releases": rels,
        })

    # 官方精选里排行没有的条目（如 gamehub/eden/termux）单独并入
    known_repos = {e["repo"] for e in emus}
    for o in OFFICIAL:
        if o["repo"] and o["repo"] in known_repos:
            continue
        emus.append({
            "id": o["id"],
            "name": o["name"],
            "repo": o["repo"],
            "repo_url": f"https://github.com/{o['repo']}" if o["repo"] else "",
            "category": "Winlator" if o["id"] in ("winlator", "gamehub", "eden") else "PC Emulator",
            "official": True,
            "channel": "stable",
            "source_kind": "official",
            "downloads": 0,
            "stars": 0,
            "description": "",
            "pushed_at": "",
            "homepage": o["homepage"],
            "releases": [],
        })

    emus.sort(key=lambda e: (-(1 if e["official"] else 0), -(e.get("downloads") or 0)))
    cats = {}
    for e in emus:
        c = e["category"]
        cats[c] = cats.get(c, 0) + 1
    doc = {
        "updated_at": t3.get("updatedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "source": "https://t3st31.github.io/Ranking-Emulators-Download/ + the412banner/emulator-ranking",
        "categories": [{"key": c, "count": n} for c, n in sorted(cats.items(), key=lambda x: -x[1])],
        "emulators": emus,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"已更新：{len(emus)} 条 / {len(cats)} 个分类（排除 Drivers {len(results)-len(emus)-sum(1 for r in results if (r.get('category') or '')=='Drivers')+sum(1 for r in results if (r.get('category') or '')=='Drivers')}）")
    print("分类:", cats)


if __name__ == "__main__":
    main()
