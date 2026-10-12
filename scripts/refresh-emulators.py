#!/usr/bin/env python3
"""刷新 emulator-registry.json：拉取各模拟器 GitHub 仓库活跃度与最新版本。

- 对每个有 owner/repo 的条目：
  - 拉 repo 元数据（pushed_at / stargazers_count）
  - 拉最新 release：取 tag_name 与第一个 apk 资产下载 URL（GitHub release 资产域名）
- 无仓库（GameHub/Eden 等仅官网）保持原样，latest_tag 留空由 UI 显示"官网获取"
- 无变化时静默退出（exit 0），有变化时由调用方提交

用法：GITHUB_TOKEN=xxx python3 scripts/refresh-emulators.py
"""
import json
import os
import sys
import time
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN", "")
if not TOKEN:
    print("缺少 GITHUB_TOKEN", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(REPO_ROOT, "emulator-registry.json")

API = "https://api.github.com/repos/{owner}/{repo}"
API_RELEASES = API + "/releases?per_page=5"


def api_get(url, retries=2):
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "emubox-ci")
    for i in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 403 and i < retries:
                time.sleep(3)
                continue
            return None
        except Exception:
            return None
    return None


def main():
    with open(REGISTRY, encoding="utf-8") as f:
        data = json.load(f)

    changed = False
    for emu in data["emulators"]:
        owner, repo = emu.get("owner", ""), emu.get("repo", "")
        if not owner or not repo:
            continue
        meta = api_get(API.format(owner=owner, repo=repo))
        if meta:
            pushed = meta.get("pushed_at", "") or ""
            stars = meta.get("stargazers_count", 0) or 0
            if pushed != emu.get("pushed_at") or stars != emu.get("stars"):
                emu["pushed_at"] = pushed
                emu["stars"] = stars
                changed = True
        rel = api_get(API_RELEASES.format(owner=owner, repo=repo))
        if rel:
            for r in rel:
                if r.get("draft") or r.get("prerelease"):
                    continue
                assets = r.get("assets", [])
                apk = next((a for a in assets if a["name"].lower().endswith(".apk")), None)
                tag = r.get("tag_name", "")
                url = apk["browser_download_url"] if apk else ""
                if tag != emu.get("latest_tag") or url != emu.get("latest_apk_url"):
                    emu["latest_tag"] = tag
                    emu["latest_apk_url"] = url
                    changed = True
                break
        time.sleep(0.2)

    if changed:
        data["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        with open(REGISTRY, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("emulator-registry.json 已更新")
    else:
        print("无变化")


if __name__ == "__main__":
    main()
