#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给每个项目生成一句话摘要，来源优先级：

1. 仓库 README 首段   —— 作者自己写的「这是什么」，最贴近官方
2. 仓库 About 描述     —— README 缺失或首段是徽章墙时的兜底
3. 最近发布说明首句     —— 前两者都没有的仓库（大量 fork 属于这种）

输出写到 emulator-registry.json 的 `summary` 字段；`description` 保留作者原文不动。

用法：
    python3 scripts/fetch_summaries.py --limit 20 --dry   # 抽样看质量
    python3 scripts/fetch_summaries.py --apply            # 全量写回
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
REG = os.path.join(ROOT, "emulator-registry.json")
CACHE = os.path.join(ROOT, "data", "readme-cache")

UA = {"User-Agent": "Mozilla/5.0 (compatible; emubox-summary/1.0)"}
README_NAMES = ["README.md", "readme.md", "Readme.md", "README.MD", "README.rst"]

# README 里这些段落不是「这是什么」，跳过
SKIP_HEADING = re.compile(
    r"^(table of contents|contents|目录|安装|install|installation|build|building|"
    r"compil|download|下载|usage|用法|license|许可|credit|acknowledg|contribut|"
    r"changelog|更新|faq|screenshot|截图|donate|赞助|support|支持我们|disclaimer|"
    r"note|notes|warning|known issue|todo|roadmap|links?|相关链接)",
    re.I,
)

BADGE = re.compile(r"^\s*(\[!\[|<img|<a\s|<p\s|<div|\[!\w|\]\(http|!\[)", re.I)
# 这些行不是「这是什么」：许可证头、语言切换、纯链接导航、setext 标题下划线
NOISE = re.compile(
    r"(SPDX-License-Identifier|SPDX-FileCopyrightText|Copyright \(c\)|"
    r"^English\b|\bEnglish\*\*\s*\||^\|?\s*(English|简体中文|日本語|한국어)\s*\||"
    r"^=+$|^-{3,}$|^\*{3,}$|^(weekly|update|news|announce|latest builds)\b)", re.I)
HTML_TAG = re.compile(r"<[^>]+>")
MD_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
MD_IMG = re.compile(r"!\[[^\]]*\]\([^)]*\)")
EMOJI_LEAD = re.compile(r"^[\s\W_]+", re.U)


def http_text(url, timeout=15, limit=400_000):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(limit).decode("utf-8", "ignore")


def readme_url(repo):
    """缓存分支探测结果：main / master 只试一次，避免每条都撞两次 404。"""
    for branch in ("main", "master"):
        for name in README_NAMES:
            url = f"https://cdn.jsdelivr.net/gh/{repo}@{branch}/{name}"
            try:
                txt = http_text(url)
                if len(txt) > 80:
                    return url, txt
            except urllib.error.HTTPError as ex:
                if ex.code == 404:
                    continue
                raise
            except Exception:
                continue
    return None, None


def clean(text):
    """剥掉徽章、HTML、导航链接、许可证头，按段落找第一段真正的介绍。

    按「段落」而不是「行」来拼：很多 README 一句话就是一行 500 字符，
    逐行截断会把描述切掉一半。
    """
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    paras, cur = [], []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            if cur:
                paras.append(" ".join(cur))
                cur = []
            continue
        if line.startswith(("#", "|", "[!", "```")):
            continue
        if BADGE.match(line) or NOISE.search(line):
            continue
        # 一行三个以上链接、且剥掉链接后文字很少 → 是导航栏（Features | Download | Build）
        # 但术语解释型链接（PCSX2 的 Interpreters/Recompilers 各链维基）要保留
        bare = MD_LINK.sub(r"\1", line)
        if line.count("](") >= 3 and len(bare) < 90:
            continue
        # 整行以裸 URL 结尾且不长 → 是下载指引不是介绍
        if re.search(r"https?://\S+$", line) and len(line) < 240:
            continue
        # "Homepage | Forum | Wiki | Buildbot" 这类纯导航
        if line.count(" | ") >= 3:
            continue
        line = MD_IMG.sub("", line)
        line = MD_LINK.sub(r"\1", line)
        line = re.sub(r"\(https?://[^)]+\)", "", line)
        line = HTML_TAG.sub(" ", line)
        line = line.replace("**", "").replace("__", "")
        line = re.sub(r"\s+", " ", line).strip(" >|*-·•=")
        if len(line) < 8:
            continue
        cur.append(line)
    if cur:
        paras.append(" ".join(cur))

    for p in paras:
        if 40 <= len(p) <= 800:
            return p
    return paras[0] if paras else ""


def trim(s, limit=180):
    """按句子边界截断，找不到句号就硬截。中英文标点都算。"""
    s = s.strip()
    if len(s) <= limit:
        return s
    cut = max(s.rfind("。", 0, limit), s.rfind(". ", 0, limit), s.rfind("！", 0, limit),
              s.rfind("? ", 0, limit), s.rfind("；", 0, limit))
    if cut > limit * 0.4:
        return s[: cut + 1].strip()
    return s[:limit].rstrip(" ,;，；、") + "…"


def from_latest_body(body):
    if not body:
        return ""
    txt = MD_IMG.sub("", body)
    txt = MD_LINK.sub(r"\1", txt)
    txt = HTML_TAG.sub(" ", txt)
    for line in txt.splitlines():
        line = re.sub(r"\s+", " ", line).strip(" #*-·•>")
        if len(line) < 12:
            continue
        if re.search(r"(what'?s changed|changelog|full changelog|更新内容|下载)", line, re.I):
            continue
        return trim(line, 140)
    return ""


def one(e):
    eid = e["id"]
    repo = e.get("repo") or ""
    cache_file = os.path.join(CACHE, eid.replace("/", "_") + ".txt")

    if not repo or "/" not in repo:
        return eid, e.get("summary") or "", "no-repo"

    text = ""
    if os.path.exists(cache_file):
        text = open(cache_file, encoding="utf-8").read()
    else:
        try:
            _, text = readme_url(repo)
        except Exception:
            text = ""
        os.makedirs(CACHE, exist_ok=True)
        if text:
            open(cache_file, "w", encoding="utf-8").write(text)

    if text:
        s = trim(clean(text))
        if len(s) >= 20 and not re.match(r"(weekly|update|news|announce)\b", s, re.I):
            return eid, s, "readme"

    about = (e.get("description") or "").strip()
    if len(about) >= 15:
        return eid, trim(about, 160), "about"

    body = from_latest_body(e.get("latest_body") or "")
    if body:
        return eid, body, "release"
    return eid, e.get("summary") or "", "none"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--only", default="", help="逗号分隔的 id，用于重跑个别条目")
    a = ap.parse_args()

    doc = json.load(open(REG, encoding="utf-8"))
    ems = doc["emulators"]
    if a.only:
        want = {x.strip() for x in a.only.split(",") if x.strip()}
        ems = [e for e in ems if e["id"] in want]
    elif a.limit:
        ems = ems[: a.limit]

    # 已有中文摘要的（手工写过）不覆盖
    results = {}
    from collections import Counter
    src = Counter()
    with ThreadPoolExecutor(10) as pool:
        for i, (eid, s, via) in enumerate(pool.map(one, ems), 1):
            results[eid] = s
            src[via] += 1
            if a.dry:
                print(f"[{via}] {eid}\n    {s[:150]}")
            elif i % 40 == 0:
                print(f"{i}/{len(ems)}", flush=True)

    print("来源分布:", dict(src))
    if not a.apply:
        print("（未写回，加 --apply 生效）")
        return

    changed = 0
    for e in doc["emulators"]:
        s = results.get(e["id"])
        cur = (e.get("summary") or "").strip()
        # 已经人工写过中文摘要的不覆盖（README 是英文，覆盖等于降级）
        if cur and re.search(r"[\u4e00-\u9fff]", cur) and len(cur) >= 12:
            continue
        if s and s != e.get("summary"):
            e["summary"] = s
            changed += 1
    json.dump(doc, open(REG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"写回 {changed} 条摘要")


if __name__ == "__main__":
    main()
