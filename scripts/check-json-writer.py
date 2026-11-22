#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""闸门：scripts/ 下不允许直接调 json.dump 写文件。

背景
----
2026-11-22 收录 45 条时，写回脚本用了 ``indent=1``，而注册表是 ``indent=2``，
一次提交报出 18.3 万行改动——真正新增的 5913 行被淹没在空白差异里，review
等于没做。根因不是那次写错，而是仓库里 29 个脚本各自调 ``json.dump``、各自
决定缩进和末尾换行：同一个文件被不同脚本轮流写，格式就随「谁最后跑」漂移。

现在写文件统一走 ``scripts/jsonio.save``：写前嗅探原文件格式，内容没变就不
落盘。这个检查只拦一件事——新增的裸 ``json.dump``。

``json.dumps`` 不受影响：它只负责把对象序列化成字符串（脚本里大量用于比较
前后是否变化），不写文件。
"""
import re
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
ALLOW = {
    # 它就是那个统一入口，内部当然要调 json.dump
    "jsonio.py",
}
CALL = re.compile(r"\bjson\.dump\s*\(")


def main() -> int:
    bad = []
    scanned = 0
    for path in sorted(SCRIPTS.rglob("*.py")):
        rel = path.relative_to(SCRIPTS).as_posix()
        if rel in ALLOW:
            continue
        scanned += 1
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.lstrip()
            if stripped.startswith("#"):
                continue
            if CALL.search(line):
                bad.append((rel, lineno, stripped))

    if bad:
        print("以下位置直接调用了 json.dump，会让文件格式随脚本漂移：")
        for rel, lineno, line in bad:
            print(f"  {rel}:{lineno}  {line[:90]}")
        print()
        print("改用：from jsonio import save  →  save(path, obj)   # 格式跟着原文件走")
        print("显式指定：save(path, obj, indent=2) / save(path, obj, indent=None)  # 单行紧凑")
        return 1

    print(f"json 写入口检查通过：{scanned} 个脚本，全部走 jsonio.save")
    return 0


if __name__ == "__main__":
    sys.exit(main())
