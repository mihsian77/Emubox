#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文案规范检查：防止项目简介再退回「内部整理笔记」状态。

背景（2026-11）：353 条简介里曾同时存在 96 条未翻译的英文原文、72 条夹带
★ 星数/停更日期/fork 谱系/核对结论的编辑腔，另有 257 条 note 是审核过程
记录，而 note 在卡片上是红字展示——等于把我们自己的工作笔记直接推给用户。

规则见 CONTRIBUTING.md「项目文案怎么写」。本脚本把其中可机器判定的部分固化，
接在 CI 里，不合规就让构建红掉。

用法：python3 scripts/check-copy.py [--warn-only]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "data/emulator-registry.json")

# description 里不该出现的：可变数字、内部流程词、谱系考据
DESC_BANNED = [
    (re.compile(r"★"), "含星标数"),
    (re.compile(r"\b20\d\d[-/]\d\d"), "含日期"),
    (re.compile(r"待核|待补|TODO"), "含待办标记"),
    (re.compile(r"直链"), "含内部术语「直链」"),
    (re.compile(r"本站|本库|我们"), "含第一人称叙述"),
    (re.compile(r"实测|复核|溯源|核对"), "含审核过程词"),
    (re.compile(r"改判|前次|原记录|收录理由|归入家族"), "含收录决策记录"),
    (re.compile(r"同源|重叠 \d+/\d+|归档备用|占位"), "含源比对结论"),
    (re.compile(r"库内|条目数|此前为空"), "含库存统计"),
]

# note 只允许解释「为什么没有下载按钮」与「使用前提」，长度不超过 60 字
NOTE_BANNED = [
    (re.compile(r"★"), "含星标数"),
    (re.compile(r"\b20\d\d[-/]\d\d"), "含日期"),
    (re.compile(r"溯源|复核|改判|前次|原记录|库内|归入|证据：|收录"), "含审核记录"),
]
NOTE_MAX = 60
DESC_MAX_WARN = 90


def main():
    warn_only = "--warn-only" in sys.argv
    ems = json.load(open(REGISTRY, encoding="utf-8")).get("emulators") or []
    errors, warns = [], []

    for e in ems:
        eid, name = e.get("id"), e.get("name")
        desc = (e.get("description") or "").strip()
        note = (e.get("note") or "").strip()

        if not desc:
            errors.append(f"{eid}（{name}）：description 为空")
        else:
            if not re.search(r"[\u4e00-\u9fff]", desc):
                errors.append(f"{eid}（{name}）：description 未翻译成中文")
            for pat, why in DESC_BANNED:
                if pat.search(desc):
                    errors.append(f"{eid}（{name}）：description {why} → {desc[:50]}")
            if len(desc) > DESC_MAX_WARN:
                warns.append(f"{eid}：description {len(desc)} 字，建议压到 {DESC_MAX_WARN} 字以内")

        if note:
            for pat, why in NOTE_BANNED:
                if pat.search(note):
                    errors.append(f"{eid}（{name}）：note {why} → {note[:50]}")
            if len(note) > NOTE_MAX:
                errors.append(f"{eid}（{name}）：note {len(note)} 字，超过 {NOTE_MAX} 字上限")

    for w in warns:
        print(f"::warning::{w}")
    if warns:
        print(f"\n{len(warns)} 条提示（不阻断）")
    if errors:
        print(f"\n{len(errors)} 条违规：")
        for x in errors:
            print("  " + x)
        if not warn_only:
            return 1
    else:
        print(f"文案规范检查通过：{len(ems)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
