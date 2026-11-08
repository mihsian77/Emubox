#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守住监控分工：仓库只跑元数据刷新，社区动态一律在本机。

分工的唯一定义在 docs/monitoring.md。这个脚本只做一件事：
在 CI 里挡住「把社媒/视频/网盘采集塞回仓库」的提交。

判定：
  1. .github/workflows/*.yml  出现外部社媒/视频/网盘域名  -> 失败
  2. scripts/*.py             出现同样的域名              -> 失败
     （确有正当用途的行，行尾写 `# monitor-scope: allow`）
  3. 仓库里出现 data/intl-news/                          -> 失败
  4. docs/monitoring.md 缺失                             -> 失败

退出码 0 通过，1 失败。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SELF = os.path.abspath(__file__)

# 只列「社区动态」性质的站。github.com / api.github.com 是元数据源，不在此列。
PATTERNS = [
    r"youtube\.com", r"youtu\.be",
    r"reddit\.com",
    r"t\.me/", r"telegram\.me",
    r"(?<![a-z0-9.-])x\.com/", r"twitter\.com",
    r"bilibili\.com", r"b23\.tv",
    r"weibo\.com", r"coolapk\.com", r"douyin\.com",
    r"xiaohongshu\.com", r"tieba\.baidu\.com",
    r"123pan\.com", r"123912\.com",
    r"pan\.baidu\.com", r"pan\.quark\.cn", r"lanzou[a-z]?\.com",
]
REGEX = [(p, re.compile(p, re.I)) for p in PATTERNS]
ALLOW_MARK = "# monitor-scope: allow"


def scan(path, rel):
    if os.path.abspath(path) == SELF:
        return []
    hits = []
    try:
        with open(path, encoding="utf-8") as f:
            for no, line in enumerate(f, 1):
                if ALLOW_MARK in line.lower():
                    continue
                for pat, rx in REGEX:
                    if rx.search(line):
                        hits.append((rel, no, pat, line.strip()[:100]))
                        break
    except UnicodeDecodeError:
        pass
    return hits


def main():
    hits = []

    wf_dir = os.path.join(ROOT, ".github", "workflows")
    if os.path.isdir(wf_dir):
        for name in sorted(os.listdir(wf_dir)):
            if name.endswith((".yml", ".yaml")):
                hits += scan(os.path.join(wf_dir, name),
                             ".github/workflows/" + name)

    sc_dir = os.path.join(ROOT, "scripts")
    if os.path.isdir(sc_dir):
        for name in sorted(os.listdir(sc_dir)):
            if name.endswith(".py"):
                hits += scan(os.path.join(sc_dir, name), "scripts/" + name)

    if os.path.isdir(os.path.join(ROOT, "data", "intl-news")):
        hits.append(("data/intl-news/", 0, "-", "仓库内不应存在社媒采集产物目录"))

    if not os.path.isfile(os.path.join(ROOT, "docs", "monitoring.md")):
        hits.append(("docs/monitoring.md", 0, "-", "分工文档缺失"))

    if hits:
        print("监控分工校验失败：社区动态采集不允许进仓库。")
        print("定义见 docs/monitoring.md；确实需要的行加 `%s`。" % ALLOW_MARK)
        print()
        for rel, no, pat, line in hits:
            loc = "%s:%d" % (rel, no) if no else rel
            print("  %-46s %-20s %s" % (loc, pat, line))
        return 1

    print("监控分工校验通过：仓库内 0 处社媒/网盘采集。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
