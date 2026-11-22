#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 android_ready 补上判定依据，并清掉「没依据的 false」。

问题：这个字段被当成「能不能在安卓上跑」用（note 里跟着写「安卓端无官方版本」），
实际判定逻辑是「GitHub release 里有没有 .apk」。官网 / Play / 自建托管分发的项目
因此全被记成 false，183 条里至少十几条与事实相反（ScummVM、Dolphin 官方都有安卓版）。

本脚本只做两件事，不改任何「有依据」的值：
  1. 有依据的 true/false 标 android_ready_source=release（依据是发布资产）
  2. 既没有发布资产、也没有人工核实的 false，把值置 null 并标 unknown
     —— 「不知道」比「不支持」诚实，UI 端 optBoolean 兜底仍是 false，不会变天

人工核实过的条目走 MANUAL 表，改完记得在提交信息里写清依据来源。

用法: python3 scripts/derive_android_ready_source.py [--dry]
"""
import json
import os
import sys
from collections import Counter

from jsonio import save

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "data/emulator-registry.json")

# 官方渠道确实提供安卓版，只是不发在 GitHub release 里。
# 依据：官方下载页 / 官方 Play 页面存在对应条目（2026-11-20 逐条核过）。
MANUAL = {
    "dolphin": "官方站提供 Android 版",
    "mgba": "官网 Download 页含 Android 版",
    "melonds": "官网与 Play 均提供 Android 版",
    "ppsspp": "官方 Play 页面在架",
    "redream-dc": "官网提供 Android 版",
    "nexium-switch1": "官网分发 Android 包",
    "scummvm": "官方下载页含 Android APK（2026.3.0）",
    "nyushu": "官网提供 Android 版，另有联发科 v76 分支",
}


def artifacts_total(e):
    a = e.get("artifacts")
    if not isinstance(a, dict):
        return 0
    return sum(v for k, v in a.items() if k != "total" and isinstance(v, int))


def main():
    d = json.load(open(REG, encoding="utf-8"))
    ems = d["emulators"]
    ids = {e["id"] for e in ems}
    missing = set(MANUAL) - ids
    if missing:
        raise SystemExit(f"MANUAL 里的 id 不在库中：{sorted(missing)}")

    moves = Counter()
    changed = 0

    for e in ems:
        before = json.dumps(e, ensure_ascii=False, sort_keys=True)
        eid = e["id"]
        val = e.get("android_ready")
        if eid in MANUAL:
            e["android_ready"] = True
            e["android_ready_source"] = "manual"
            moves[("manual", val, True)] += 1
        elif val is True:
            e["android_ready_source"] = "release"
        elif artifacts_total(e) > 0:
            a = e.get("artifacts") or {}
            if val is None:
                # 新条目：产物里真有安卓包（apk 或 android zip）才算能装
                e["android_ready"] = (a.get("apk", 0) + a.get("android_zip", 0)) > 0
            e["android_ready_source"] = "release"
        elif val is False:
            e["android_ready"] = None
            e["android_ready_source"] = "unknown"
            moves[("unknown", False, None)] += 1
        else:
            e["android_ready_source"] = "unknown"
        if json.dumps(e, ensure_ascii=False, sort_keys=True) != before:
            changed += 1

    print(f"改动 {changed} 处")
    for (tag, o, n), c in moves.most_common():
        print(f"  {tag:<10} {o!s:<6} -> {n!s:<6} {c}")
    print("android_ready:", dict(Counter(e.get("android_ready") for e in ems)))
    print("source:", dict(Counter(e.get("android_ready_source") for e in ems)))

    if "--dry" in sys.argv:
        return
    # 写回走 jsonio：缩进与末尾换行跟着文件现状，不再由本脚本单方面决定。
    # 它同时会跳过「内容没变」的写入，派生脚本重放两次不会改 mtime。
    save(REG, d)


if __name__ == "__main__":
    main()
