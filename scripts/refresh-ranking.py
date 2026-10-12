#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓取模拟器社区排行数据（the412banner/emulator-ranking），生成 emulator-ranking.json。

数据源：https://the412banner.github.io/emulator-ranking/data/rankings.json
该站点每日自动更新（下载量、star、最新 release 与更新日志正文）。

输出：仓库根目录 emulator-ranking.json
  {
    "updated_at": "ISO 时间",
    "source": "https://the412banner.github.io/emulator-ranking/",
    "emulators": [ {id, name, repo, category, group, downloads, stars,
                    description, pushed_at, homepage, latest_tag, latest_date, latest_body} ]
  }

group 映射（对应 App 内 4 个分区）：
  windows  -> Winlator / GameHub / PC Emulator
  console  -> PS2 / PS3 / 3DS / PSVITA / GameCube-Wii / Wii U / Dreamcast / Xbox
  multi    -> All In One（RetroArch 等聚合前端）
  未命中分类跳过（Drivers 等不属于模拟器分区）
"""

import json
import os
import time
import urllib.request

RANKING_URL = "https://the412banner.github.io/emulator-ranking/data/rankings.json"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "emulator-ranking.json")

GROUP_MAP = {
    "Winlator": "windows",
    "GameHub": "windows",
    "PC Emulator": "windows",
    "Emulator PS2": "console",
    "Emulator PS3": "console",
    "Nintendo 3DS": "console",
    "PSVITA": "console",
    "Nintendo GameCube / Wii": "console",
    "Wii U Emulator": "console",
    "Sega Dreamcast": "console",
    "Xbox": "console",
    "All In One": "multi",
}

# 保留条目上限（下载量排序，控制内置体积）
LIMIT = 24

# GitHub API 未认证限额 60/h；CI 内用 GITHUB_TOKEN 提高配额
GH_TOKEN = os.environ.get("GITHUB_TOKEN", "")


def fetch(url: str, timeout: int = 30):
    req = urllib.request.Request(url, headers={"User-Agent": "emubox-ci/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def latest_apk(repo: str):
    """取仓库最新 release 中第一个 .apk 资产的直链（无则返回空）。"""
    if not repo or "/" not in repo:
        return "", ""
    api = f"https://api.github.com/repos/{repo}/releases/latest"
    req = urllib.request.Request(api, headers={"User-Agent": "emubox-ci/1.0"})
    if GH_TOKEN:
        req.add_header("Authorization", f"Bearer {GH_TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            d = json.load(r)
        for a in d.get("assets") or []:
            if a.get("name", "").endswith(".apk"):
                return a["browser_download_url"], (d.get("published_at") or "")[:10]
    except Exception:
        pass
    return "", ""


def main():
    data = fetch(RANKING_URL)
    results = data.get("results", [])
    out = []
    for r in results:
        group = GROUP_MAP.get(r.get("category"))
        if not group:
            continue
        stats = r.get("repoStats") or {}
        rel = (r.get("releases") or [])
        latest = rel[0] if rel else {}
        tag = latest.get("tag") or ""
        date = (latest.get("date") or "")[:10]
        body = (latest.get("body") or "").replace("\r", "")
        apk_url, rel_date = latest_apk(r.get("repo", ""))
        entry = {
            "id": (r.get("repo") or r.get("name", "")).replace("/", "-"),
            "name": r.get("name", ""),
            "repo": r.get("repo", ""),
            "category": r.get("category", ""),
            "group": group,
            "downloads": r.get("downloads", 0),
            "stars": stats.get("stars", 0),
            "description": stats.get("description", ""),
            "pushed_at": stats.get("pushedAt", ""),
            "homepage": stats.get("homepage", ""),
            "latest_tag": tag,
            "latest_date": rel_date or date,
            "latest_apk_url": apk_url,
            "latest_body": body[:1200],
        }
        out.append(entry)

    out.sort(key=lambda e: e["downloads"], reverse=True)
    out = out[:LIMIT]
    with_apk = sum(1 for e in out if e["latest_apk_url"])
    doc = {
        "updated_at": data.get("updatedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "source": "https://the412banner.github.io/emulator-ranking/",
        "emulators": out,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"emulator-ranking.json 已更新：{len(out)} 条（含 APK 直链 {with_apk} 条）")


if __name__ == "__main__":
    main()
