#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JSON 格式漂移检查：内容改动不应顺带把整个文件重排。

背景（2026-11-22）：往注册表里追加 45 条，实际只该有 5.9k 行新增，
提交时却是 18.3 万行改动——写回脚本用了 indent=1，而文件是 indent=2，
git 认为每一行都变了。后果不只是 diff 难看：blame 全废、review 时
真正的内容改动被淹没、任何按行比对的工具（冲突解决、审计）都失效。

仓库里 30 个 JSON 分属四种写法（compact / indent=0 / 1 / 2），本身是
合理的——App 直连加载的用 compact 省流量，人读的用 2 空格。问题不在
风格不统一，而在「某个脚本悄悄换掉了某个文件的风格」。

本脚本不比风格优劣，只比「和上一版是否一致」：从 HEAD 取同一文件的
写法做基线，当前文件写法变了就报错。新增文件不受约束。

要正式改某个文件的格式时，单独提交并接受一次大 diff，不要混在数据改动里。

用法: python3 scripts/check-json-style.py [--warn-only]
"""
import glob
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERNS = ["data/*.json", "app/src/main/assets/*.json", "bench-data/*.json", "catalog/*.json"]
MAX_BYTES = 40 * 1024 * 1024


def style_of(data):
    """(缩进描述, 是否以换行结尾)。只看前 4KB，足够判断首行结构。"""
    tail_nl = data.endswith(b"\n")
    head = data[:4096]
    if b"\n" not in head:
        return "compact", tail_nl
    for ln in head.split(b"\n")[1:]:
        if not ln.strip():
            continue
        return f"indent={len(ln) - len(ln.lstrip(b' '))}", tail_nl
    return "unknown", tail_nl


def head_bytes(rel):
    try:
        r = subprocess.run(["git", "-C", ROOT, "show", f"HEAD:{rel}"],
                           capture_output=True, timeout=60)
        return r.stdout if r.returncode == 0 and r.stdout else None
    except Exception:
        return None


def main():
    warn_only = "--warn-only" in sys.argv
    problems = []
    checked = skipped = 0

    files = []
    for p in PATTERNS:
        files.extend(glob.glob(os.path.join(ROOT, p)))
    for path in sorted(set(files)):
        rel = os.path.relpath(path, ROOT)
        if os.path.getsize(path) > MAX_BYTES:
            skipped += 1
            continue
        base = head_bytes(rel)
        if base is None:
            skipped += 1          # 新增文件，没有基线可比
            continue
        checked += 1
        cur = open(path, "rb").read()
        sb, nb = style_of(base)
        sc, nc = style_of(cur)
        if sb != sc:
            problems.append(f"[{rel}] 缩进 {sb} → {sc}：整文件被重排，内容改动会被淹没")
        if nb != nc:
            problems.append(f"[{rel}] 末尾换行 {'有' if nb else '无'} → {'有' if nc else '无'}")

    print(f"检查 {checked} 个 JSON（跳过 {skipped} 个新增/超大文件），发现 {len(problems)} 处格式漂移")
    for p in problems:
        print("  ", p)
    if problems:
        print("\n修法：写回时沿用文件原有写法（registry 类用 indent=2 + 末尾换行）；")
        print("      确需更改格式请单独提交，并在提交说明里写明原因。")
        return 0 if warn_only else 1
    print("全部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
