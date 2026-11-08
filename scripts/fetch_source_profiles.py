#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为驱动/组件源抓取 README，抽「给谁用 / 怎么装」的证据句。

输出两个东西：
- data/source-readme/<id>.md      原始 README（裁剪到 60KB），供人工复核
- stdout                          每条源的证据摘要（含 install 关键行、适用机型行、警告行）

用法：
    python3 scripts/fetch_source_profiles.py            # 全量抓取 + 摘要
    python3 scripts/fetch_source_profiles.py --only stevenmxz,k11mch1
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "catalog", "catalog.json")
CACHE = os.path.join(ROOT, "data", "source-readme")
UA = {"User-Agent": "Mozilla/5.0 (compatible; emubox-profile/1.0)"}

OWNER_RE = re.compile(
    r"(?:github\.com|raw\.githubusercontent\.com)/(?:repos/)?([^/]+)/([^/]+?)(?:/|$)", re.I)
NAMES = ["README.md", "readme.md", "Readme.md", "README.MD", "README.rst", "README.txt"]

# 装法证据：驱动包怎么导入、放哪个目录、配合哪个模拟器
INSTALL_RE = re.compile(
    r"(adpkg|\.wcp|\.tzst|import|install|copy|push|adb|contents|driver folder|"
    r"graphics driver|container|settings|winlator|eden|skyline|strato|vita3k|yuzu|"
    r"turnip|panv[ks]|wrapper)", re.I)
# 适用证据：芯片/系列/机型
FIT_RE = re.compile(
    r"(adreno|mali|a6\d\d|a7\d\d|a8\d\d|g5\d|g7\d|snapdragon|dimensity|exynos|xclipse|"
    r"gen\s*[1-8]|6xx|7xx|8xx|support)", re.I)
WARN_RE = re.compile(r"(⚠|warning|not supported|unsupported|broken|crash|experimental|"
                     r"deprecated|do not|only work|known issue|black screen|artifact)", re.I)


def get(url, limit=200_000):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read(limit).decode("utf-8", "ignore")


def clean(md):
    md = re.sub(r"<!--.*?-->", "", md, flags=re.S)
    md = re.sub(r"<[^>]+>", "", md)
    # 徽章墙：![...](...) 与 [![...](...)](...)
    md = re.sub(r"\[!\[[^\]]*\]\([^)]*\)\]\([^)]*\)", "", md)
    md = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", md)
    md = re.sub(r"\n{3,}", "\n\n", md)
    return md


def fetch_one(item):
    sid, url = item
    m = OWNER_RE.search(url or "")
    if not m:
        return sid, "", "no-repo"
    owner, repo = m.group(1), m.group(2)
    for branch in ("main", "master"):
        for name in NAMES:
            try:
                txt = get(f"https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{name}")
                return sid, clean(txt), f"{owner}/{repo}@{branch}/{name}"
            except urllib.error.HTTPError:
                continue
            except Exception as e:  # noqa: BLE001
                return sid, "", f"err {e}"
    return sid, "", f"{owner}/{repo} no-readme"


def digest(sid, md):
    lines = [ln.strip() for ln in md.splitlines()]
    head = [ln for ln in lines if ln and not ln.startswith("#")][:6]
    inst = [ln for ln in lines if INSTALL_RE.search(ln) and 20 < len(ln) < 220][:8]
    fit = [ln for ln in lines if FIT_RE.search(ln) and 15 < len(ln) < 200][:6]
    warn = [ln for ln in lines if WARN_RE.search(ln) and 15 < len(ln) < 200][:6]
    out = [f"### {sid}", "HEAD:"] + [f"  {h[:200]}" for h in head]
    for tag, rows in (("INSTALL", inst), ("FIT", fit), ("WARN", warn)):
        if rows:
            out.append(f"{tag}:")
            out += [f"  {r[:200]}" for r in rows]
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    cat = json.load(open(CATALOG, encoding="utf-8"))
    items = []
    for key in ("turnipSources", "qualcommSources", "componentSources"):
        for s in cat.get(key, []):
            items.append((s["id"], s.get("apiUrl") or s.get("manifestUrl") or ""))
    if args.only:
        keep = {x.strip() for x in args.only.split(",")}
        items = [i for i in items if i[0] in keep]
    os.makedirs(CACHE, exist_ok=True)
    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(fetch_one, items))
    for sid, md, meta in results:
        if md:
            with open(os.path.join(CACHE, f"{sid}.md"), "w", encoding="utf-8") as f:
                f.write(md[:60_000])
            print(digest(sid, md))
        else:
            print(f"### {sid}\n  skipped: {meta}")
        print()


if __name__ == "__main__":
    main()
