#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 assets/source-profiles.json（驱动/组件源的「给谁用 · 怎么装 · 注意」文案）。

两级来源，都不许编：

1. catalog/source-profiles.json —— 手写文案。只写能从 README / 发布说明里读到的事实。
2. driver-registry.json —— 手写文案没覆盖的源（注册表里 40 多条），用已有字段拼：
   - fit      取该条目的 description（此前已按仓库自述写过，不是新编）
   - how      按 driver_type / package_format / target_emulator 决定装法
              （adpkg 导入、magisk 刷入、wcp 组件、wrapper 挂载……都是格式本身决定的）
   - caution  仅当 notes 里出现风险措辞时搬过来，否则留空

这样每个出现在驱动页的源都有「怎么装」，缺少依据的字段宁可为空。

用法：
    python3 scripts/build_source_profiles.py
    python3 scripts/build_source_profiles.py --check   # 只检查是否有源漏文案
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HAND = os.path.join(ROOT, "catalog", "source-profiles.json")
REGISTRY = os.path.join(ROOT, "driver-registry.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "source-profiles.json")

RISK_LEAD = re.compile(r"^(需|仅|只能|实验|不稳|混有|注意|留意|CI|Magisk|带|含)")
SOURCE_ONLY = re.compile(r"只发源码|没有驱动包|无驱动包|不提供二进制")

# 装法模板：格式决定动作，不猜
HOW_BY_FORMAT = {
    "adpkg": [
        "下载 .adpkg / .zip 驱动包",
        "模拟器「设置 → 驱动」里导入并选中",
        "回到游戏重启（部分模拟器要重开容器）",
    ],
    "zip": [
        "下载 zip 解压或直接导入",
        "模拟器「设置 → 驱动」里选中",
        "回到游戏重启",
    ],
    "so": [
        "下载 .so 驱动库",
        "按 README 说明放进 Contents 对应目录",
        "重启模拟器或容器",
    ],
    "tzst": [
        "下载 .tzst 包装层",
        "Winlator 容器里作为 Wrapper 挂载",
        "配合对应驱动一起用",
    ],
    "wcp": [
        "下载 .wcp 组件包",
        "Winlator「组件」里导入",
        "容器组件设置里切成这个版本",
    ],
    "magisk": [
        "下载 Magisk 模块 zip",
        "Magisk / KernelSU 刷入并重启",
        "系统级生效，不需要在模拟器里导入",
    ],
}

HOW_QUALCOMM = [
    "下载 Qualcomm 驱动包",
    "导入为系统/模拟器驱动",
    "与 Turnip 二选一，别叠装",
]

HOW_WRAPPER = [
    "下载 wrapper 包（.tzst / .zip）",
    "Winlator 容器里选为 Wrapper",
    "配合对应的 PanVK / Turnip 驱动使用",
]


def how_for(entry):
    dtype = entry.get("driverType") or ""
    fmt = (entry.get("packageFormat") or "").lower()
    target = (entry.get("targetEmulator") or "").lower()
    # 目标平台优先于通用格式：Winlator / Eden 的导入入口和模拟器不一样
    if target == "winlator" and dtype in ("panvk", "radv", "turnip", "qualcomm"):
        return ["下载驱动包", "Winlator 容器「显卡驱动」里导入", "重启容器生效"]
    if target == "eden" and dtype in ("panvk", "radv", "turnip"):
        return ["下载驱动包", "Eden「设置 → 驱动」导入", "选中后重启游戏"]
    # 其次看格式：装法由产物格式决定，比仓库类型更准
    if fmt in HOW_BY_FORMAT:
        return HOW_BY_FORMAT[fmt]
    if dtype == "qualcomm":
        return HOW_QUALCOMM
    if dtype == "wrapper":
        return HOW_WRAPPER
    if dtype in ("panvk", "radv"):
        return ["下载驱动包", "Winlator 容器「显卡驱动」里导入", "重启容器生效"]
    if dtype in ("turnip",):
        return HOW_BY_FORMAT["adpkg"]
    if dtype == "component":
        return HOW_BY_FORMAT["wcp"]
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    hand = json.load(open(HAND, encoding="utf-8"))
    sources = dict(hand.get("sources", {}))
    registry = json.load(open(REGISTRY, encoding="utf-8")).get("repositories", [])

    added, missing = 0, []
    for r in registry:
        sid = r.get("id")
        if not sid or sid in sources:
            continue
        desc = (r.get("description") or "").strip()
        how = how_for({
            "driverType": r.get("driver_type"),
            "packageFormat": r.get("package_format"),
            "targetEmulator": r.get("target_emulator"),
        })
        notes = (r.get("notes") or "").strip()
        caution = notes if notes and RISK_LEAD.search(notes) else ""
        if SOURCE_ONLY.search(desc):
            how = []
        if not desc and not how:
            missing.append(sid)
            continue
        sources[sid] = {
            "fit": desc,
            "how": how,
            "caution": caution,
            "generated": True,
        }
        added += 1

    out = {
        "version": hand.get("version", 1),
        "updated_at": hand.get("updated_at", ""),
        "note": hand.get("note", ""),
        "sources": dict(sorted(sources.items())),
        "componentTypes": hand.get("componentTypes", {}),
    }

    if args.check:
        print(f"手写 {len(hand.get('sources', {}))} 条，补生成 {added} 条，缺文案 {len(missing)} 条")
        for m in missing:
            print("  no-profile:", m)
        return

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"写入 {os.path.relpath(OUT, ROOT)}：手写 {len(hand.get('sources', {}))} + 生成 {added} = {len(sources)} 条源，"
          f"{len(out['componentTypes'])} 类组件")


if __name__ == "__main__":
    main()
