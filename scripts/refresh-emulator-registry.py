#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器官方 registry 数据补全脚本

emulator-registry.json（官方渠道占位：Termux/PPSSPP/RetroArch/Dolphin/DuckStation/Vita3K）
是静态维护文件，两源排行不含这些项目。本脚本每日用 GitHub API 给每条补全：
- releases 数组（tag/date/body/assets，最新在前）
- latest_tag / latest_date / latest_apk_url / latest_apk_name
- stars / pushed_at（活跃度）
CI 内带 GITHUB_TOKEN 跑；本地无 token 时跳过，保证可重复运行。
"""
import json, os, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "emulator-registry.json")

TOKEN = os.environ.get("GITHUB_TOKEN", "")


def gh(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "emubox-registry",
        "Accept": "application/vnd.github+json",
    })
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def fetch_meta_and_releases(repo):
    """拉仓库元数据 + 最多 30 条 releases，失败返回 (None, None)"""
    try:
        meta = gh(f"https://api.github.com/repos/{repo}")
        rels = gh(f"https://api.github.com/repos/{repo}/releases?per_page=30")
    except Exception as e:
        print(f"  跳过 {repo}: {e}")
        return None, None
    out = []
    for r in rels:
        assets = [{
            "name": a["name"],
            "size": int(a.get("size") or 0),
            "url": a.get("browser_download_url", ""),
        } for a in (r.get("assets") or []) if a.get("name")]
        out.append({
            "tag": r.get("tag_name", ""),
            "name": r.get("name", ""),
            "date": r.get("published_at", ""),
            "body": (r.get("body") or "").replace("\r", "")[:1200],
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return meta, out


def main():
    d = json.load(open(REGISTRY))
    changed = False
    for e in d.get("emulators", []):
        repo = e.get("repo", "")
        if not repo or "/" not in repo:
            print(f"  跳过（无完整 repo 路径）: {e.get('id')}")
            continue
        meta, rels = fetch_meta_and_releases(repo)
        if rels is None:
            continue
        e["stars"] = int(meta.get("stargazers_count") or 0)
        e["pushed_at"] = meta.get("pushed_at") or ""
        e["releases"] = rels
        if rels:
            r0 = next((r for r in rels if not r.get("prerelease") and (r.get("body") or "").strip()), None) \
                or next((r for r in rels if (r.get("body") or "").strip()), None) \
                or rels[0]
            e["latest_tag"] = r0["tag"]
            e["latest_date"] = r0["date"]
            e["latest_body"] = (r0.get("body") or "")[:2000]
            a0s = r0.get("assets") or []
            apks = [a for a in a0s if a.get("name","").lower().endswith(".apk")]
            if apks:
                a0 = apks[0]
                e["latest_apk_name"] = a0["name"]
                e["latest_apk_url"] = a0["url"]
                e["latest_apk_size"] = int(a0.get("size") or 0)
            elif not e.get("latest_apk_url"):
                e["latest_apk_name"] = ""
                e["latest_apk_url"] = ""
        changed = True
    if changed:
        d["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        json.dump(d, open(REGISTRY, "w"), ensure_ascii=False, indent=2)
        print(f"已更新 {len(d['emulators'])} 条，releases 总量: "
              f"{sum(len(e.get('releases') or []) for e in d['emulators'])}")
    else:
        print("无更新")


if __name__ == "__main__":
    main()
