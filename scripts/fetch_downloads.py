#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补下载量：两级来源，先吃库里已有的，再打 API。

为什么需要这个脚本：
  库里 243 条曾有 170 条下载数为空。但它们并不是「拿不到」——
  - 103 条的分资产下载数**早就存在 `builds[].downloads` 里**，只是从来没聚合到顶层；
  - 另有 89 条 GitHub release 资产根本没去读过 download_count。
  覆盖率 30% 不是因为渠道不支持，是因为没人把它们加起来。

两级：
  L1 --from-builds  零网络。把 builds[].downloads 求和写进 downloads_latest。立即可跑。
  L2 --api          走 GitHub Releases API，读全部 release 的 assets[].download_count，
                    写 downloads_lifetime / downloads_all_releases。需要 token（无 token
                    每小时 60 次，243 条要 5 轮）。

口径字段（不混用，避免「拿累计比最新版」）：
  downloads           顶层累计（历史遗留字段，来源 t412，语义=全部 release 之和）
  downloads_latest    最新一版各资产之和（L1 产出）
  downloads_basis     lifetime / latest-lower-bound / none

用法：
  python3 scripts/fetch_downloads.py --from-builds
  GITHUB_TOKEN=xxx python3 scripts/fetch_downloads.py --api --limit 50
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
CACHE = os.path.join(ROOT, ".cache", "release-downloads.json")
TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")


def load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def gh(path):
    req = urllib.request.Request(f"https://api.github.com{path}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "emubox-downloads")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r), r.headers


def api_fetch(repo, pages=2):
    """返回 (lifetime_downloads, release_count, asset_count)。"""
    total = 0
    nrel = nas = 0
    for page in range(1, pages + 1):
        try:
            rels, headers = gh(f"/repos/{repo}/releases?per_page=100&page={page}")
        except urllib.error.HTTPError as ex:
            if ex.code == 404:
                return None
            if ex.code in (403, 429):
                raise RuntimeError("rate-limited")
            raise
        if not rels:
            break
        for rel in rels:
            nrel += 1
            for a in rel.get("assets") or []:
                nas += 1
                total += a.get("download_count") or 0
        if len(rels) < 100:
            break
    return total, nrel, nas


def from_builds(emus):
    n = 0
    for e in emus:
        s = sum((b.get("downloads") or 0) for b in (e.get("builds") or []))
        if s:
            e["downloads_latest"] = s
            n += 1
        if not e.get("downloads") and s:
            e["downloads_basis"] = "latest-lower-bound"   # 只有最新一版 → 是下限
        elif e.get("downloads"):
            e["downloads_basis"] = "lifetime"
        else:
            e["downloads_basis"] = "none"
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from-builds", action="store_true")
    ap.add_argument("--api", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="本次最多请求多少个仓库")
    ap.add_argument("--pages", type=int, default=2, help="每个仓库最多翻几页 release")
    args = ap.parse_args()
    if not (args.from_builds or args.api):
        ap.error("至少给一个 --from-builds / --api")

    data = load(REG, None)
    if not data:
        sys.exit(f"读不到 {REG}")
    emus = data["emulators"]

    if args.from_builds:
        n = from_builds(emus)
        print(f"[L1] 从 builds 聚合出下载数：{n} 条")
        with_dl = sum(1 for e in emus if e.get("downloads_latest") or e.get("downloads"))
        print(f"     有任意下载数：{with_dl}/{len(emus)} ({with_dl/len(emus)*100:.0f}%)")
        with open(REG, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    if args.api:
        if not TOKEN:
            print("⚠ 没设 GITHUB_TOKEN，每小时只有 60 次请求，建议先导出 token", file=sys.stderr)
        cache = load(CACHE, {})
        todo = [e for e in emus
                if e.get("source") == "github" and "/" in (e.get("repo") or "")
                and e["repo"] not in cache]
        if args.limit:
            todo = todo[:args.limit]
        print(f"[L2] 待抓 {len(todo)} 个仓库（缓存已有 {len(cache)}）")
        done = 0
        for i, e in enumerate(todo, 1):
            repo = e["repo"]
            try:
                r = api_fetch(repo, args.pages)
            except RuntimeError:
                print(f"  限速了，停在 {repo}。缓存已保存，稍后重跑。")
                break
            except Exception as ex:
                cache[repo] = {"error": str(ex)[:100]}
                continue
            if r is None:
                cache[repo] = {"error": "404"}
                continue
            total, nrel, nas = r
            cache[repo] = {"lifetime": total, "releases": nrel, "assets": nas}
            if total:
                e["downloads"] = total
                e["downloads_lifetime"] = total
                e["downloads_basis"] = "lifetime"
            done += 1
            if i % 10 == 0:
                print(f"  {i}/{len(todo)} … 最近 {repo}={total}")
                os.makedirs(os.path.dirname(CACHE), exist_ok=True)
                with open(CACHE, "w", encoding="utf-8") as f:
                    json.dump(cache, f, ensure_ascii=False)
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
        with open(REG, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"[L2] 本次写入 {done} 个仓库")


if __name__ == "__main__":
    main()
