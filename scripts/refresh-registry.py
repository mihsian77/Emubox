#!/usr/bin/env python3
"""刷新 driver-registry.json 与 catalog/catalog.json 的仓库活跃度元数据。

- 提取两个文件中所有 GitHub 仓库（apiUrl / manifestUrl 解析 owner/repo）
- 用 GITHUB_TOKEN 拉取 pushed_at + stargazers_count
- 写回 registry 的 updated_at/stars 与 catalog 的 updatedAt/stars
- 无变化时静默退出（exit 0），有变化时由调用方提交

用法：GITHUB_TOKEN=xxx python3 scripts/refresh-registry.py
"""
import json
import os
import re
import sys
import time
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN", "")
if not TOKEN:
    print("缺少 GITHUB_TOKEN", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(REPO_ROOT, "driver-registry.json")
REGISTRY_ASSETS = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "driver-registry.json")
CATALOG = os.path.join(REPO_ROOT, "catalog", "catalog.json")

OWNER_REPO_RE = re.compile(
    r"(?:github\.com|raw\.githubusercontent\.com)/(?:repos/)?([^/]+)/([^/]+?)(?:/|$)",
    re.I,
)


def extract_owner_repo(url: str):
    m = OWNER_REPO_RE.search(url or "")
    return (m.group(1), m.group(2)) if m else None


def fetch_repo_meta(owner: str, repo: str, retries: int = 3):
    # 更新时间优先取最新 release 发布时间（published_at），fallback 仓库推送时间（pushed_at）
    def api_get(url):
        req = urllib.request.Request(url, headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "emubox-registry-refresh",
        })
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    for attempt in range(retries):
        try:
            released = ""
            latest_tag = ""
            latest_body = ""
            try:
                rel = api_get(f"https://api.github.com/repos/{owner}/{repo}/releases/latest")
                released = rel.get("published_at", "")
                latest_tag = rel.get("tag_name", "")
                latest_body = (rel.get("body") or "")[:500]
            except Exception:
                released = ""
            d = api_get(f"https://api.github.com/repos/{owner}/{repo}")
            pushed = d.get("pushed_at", "")
            stars = d.get("stargazers_count")
            updated = released or pushed
            if updated and stars is not None:
                return updated, int(stars), latest_tag, released, latest_body
        except Exception as e:
            if attempt < retries - 1:
                time.sleep(2 * (attempt + 1))
            else:
                print(f"  失败 {owner}/{repo}: {e}", file=sys.stderr)
    return None


def iso_to_epoch(iso: str):
    try:
        ts = time.mktime(time.strptime(iso.replace("Z", "GMT"), "%Y-%m-%dT%H:%M:%S%Z"))
        return int(ts * 1000)
    except Exception:
        return 0


def collect_repos(registry, catalog):
    repos = {}
    for r in registry.get("repositories", []):
        or_ = extract_owner_repo(r.get("api_url", ""))
        if or_:
            repos.setdefault(or_, []).append(("registry", r["id"]))
    for s in catalog.get("turnipSources", []) + catalog.get("qualcommSources", []):
        or_ = extract_owner_repo(s.get("apiUrl", ""))
        if or_:
            repos.setdefault(or_, []).append(("catalog", s["id"]))
    for s in catalog.get("componentSources", []):
        or_ = extract_owner_repo(s.get("manifestUrl", ""))
        if or_:
            repos.setdefault(or_, []).append(("catalog", s["id"]))
    return repos


def main():
    registry = json.load(open(REGISTRY, encoding="utf-8"))
    catalog = json.load(open(CATALOG, encoding="utf-8"))

    repos = collect_repos(registry, catalog)
    print(f"收集到 {len(repos)} 个仓库")
    meta = {}
    for or_ in repos:
        v = fetch_repo_meta(*or_)
        if v:
            meta[f"{or_[0].lower()}/{or_[1].lower()}"] = v
        time.sleep(0.1)
    print(f"拉取成功 {len(meta)}/{len(repos)}")

    changed = False

    def meta_for(url):
        or_ = extract_owner_repo(url)
        if not or_:
            return None
        return meta.get(f"{or_[0].lower()}/{or_[1].lower()}")

    # registry
    for r in registry.get("repositories", []):
        v = meta_for(r.get("api_url", ""))
        if not v:
            continue
        pushed, stars, latest_tag, latest_date, latest_body = v
        if r.get("updated_at") != pushed:
            r["updated_at"] = pushed
            changed = True
        if int(r.get("stars", 0)) != stars:
            r["stars"] = stars
            changed = True
        # 驱动更新提示：latest release 信息
        if r.get("latest_tag") != latest_tag:
            r["latest_tag"] = latest_tag
            changed = True
        if r.get("latest_date") != latest_date:
            r["latest_date"] = latest_date
            changed = True
        if r.get("latest_body") != latest_body:
            r["latest_body"] = latest_body
            changed = True

    # catalog
    for s in catalog.get("turnipSources", []) + catalog.get("qualcommSources", []):
        v = meta_for(s.get("apiUrl", ""))
        if not v:
            continue
        pushed, stars, _, _, _ = v
        if s.get("updatedAt") != iso_to_epoch(pushed):
            s["updatedAt"] = iso_to_epoch(pushed)
            changed = True
        if int(s.get("stars", 0)) != stars:
            s["stars"] = stars
            changed = True
    for s in catalog.get("componentSources", []):
        v = meta_for(s.get("manifestUrl", ""))
        if not v:
            continue
        pushed, _, _, _, _ = v
        if s.get("updatedAt") != iso_to_epoch(pushed):
            s["updatedAt"] = iso_to_epoch(pushed)
            changed = True

    if not changed:
        print("无变化，跳过提交")
        return

    json.dump(registry, open(REGISTRY, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    # 同步 assets 副本（App 端实际读取此份），保持双副本一致
    json.dump(registry, open(REGISTRY_ASSETS, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    json.dump(catalog, open(CATALOG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("已更新 registry/catalog（含 assets 副本）")


if __name__ == "__main__":
    main()
