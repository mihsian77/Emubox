#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 desc_overrides.py 里的统一简介写回 data/emulator-registry.json。

只改 description 字段（卡片与详情页展示的文案），summary 保留上游原文不动——
它仍是「这句话是从哪来的」的凭证，也是新条目的翻译底稿。

带 --check 只报告覆盖率不动文件。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from desc_overrides import OVERRIDES  # noqa: E402
from jsonio import load, save  # noqa: E402

REGISTRY = os.path.join(ROOT, "data/emulator-registry.json")


def main():
    check_only = "--check" in sys.argv
    doc = load(REGISTRY)
    ems = doc.get("emulators") or []
    ids = {e["id"] for e in ems}

    missing = sorted(set(OVERRIDES) - ids)          # 表里有、数据里没有 → id 写错了
    uncovered = sorted(ids - set(OVERRIDES))        # 数据里有、表里没有 → 漏写

    changed = 0
    for e in ems:
        new = OVERRIDES.get(e["id"])
        if new and e.get("description") != new:
            e["description"] = new
            changed += 1

    print(f"条目总数 {len(ems)}，覆盖表 {len(OVERRIDES)}，实际改写 {changed}")
    if missing:
        print(f"\n⚠ 覆盖表中 {len(missing)} 个 id 在数据里不存在（拼写错误？）：")
        for i in missing:
            print("   ", i)
    if uncovered:
        print(f"\n⚠ {len(uncovered)} 个条目没有写简介：")
        for i in uncovered:
            print("   ", i)

    if check_only:
        return 0 if not missing and not uncovered else 1

    if changed:
        save(REGISTRY, doc)
        print(f"\n已写回 {REGISTRY}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
