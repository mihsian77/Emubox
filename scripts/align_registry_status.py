#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对齐注册表的存续状态字段。

背景：本轮溯源推翻了若干「已死」判定（例如 Ryujinx 社区版仍在活跃发版、
Skyline Pine 的社区分支仍出 APK、Kenji-NX 并非闭源），但旧条目遗留的
dead / repo_status / dead_reason 字段与新结论矛盾。

处理原则：
- dead / repo_status / dead_reason 三者只描述「上游本体是否已死」，
  与「社区分支是否存活」是两件事。社区分支存活时清除 dead 标记，
  在 note 中记录社区来源。
- status 是最终对用户展示的标签，由 tag_registry.py 的 TAG_OVERRIDE 决定，
  本脚本只负责清理死字段，避免 tag 脚本把 repo_status 推成 dead。
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")

# id -> (dead, repo_status, dead_reason)
#   dead=True  : 上游本体确实已停更/下架，且无可用的一手分发
#   dead=False : 本体或其社区分支仍在维护
STATE = {
    # 社区分支仍存活 —— 清除旧的 dead 标记
    "skyline-pine": (False, "", ""),
    "skyline-enhanced-infinity": (False, "", ""),
    "skyline-tagline": (False, "", ""),
    "yuzu-citrus": (False, "", ""),
    "yuzu-plus": (False, "", ""),
    "ryujinx-benji-sc": (False, "", ""),
    "kenji-nx-android-releases": (False, "", ""),
    "ryubing-emulator": (False, "", ""),
    "torzu-emulator": (False, "", ""),
    "eclipseds-nds": (False, "", ""),
    "winlator-releases": (False, "", ""),
    "m64plusfz": (False, "", ""),
    "termux-jiaxinchen": (False, "", ""),
    "termux-plus": (False, "", ""),
    "termux-enhanced": (False, "", ""),
    "termux-aether": (False, "", ""),
    "termux-zero": (False, "", ""),
    "termux-kotlin": (False, "", ""),
    "producdevity-gamehub": (False, "", ""),
    # 本体确实已死
    "ovsky-sumi-emu": (True, "451", "原 GitHub 仓库 451 DMCA，官网域名已落入停放商"),
    "ryujinx-official": (True, "404", "2024-10 因 DMCA 停更，社区续接版见 Ryubing 条目"),
    "skyline-official": (True, "archived", "2026-08 仓库自述开发终止，源码已移除，官网不可达"),
    "drastic": (True, "", "2025-02 开发者自行下架，承诺开源未兑现，无官方分发渠道"),
    # 已死（上游本体）
    "yuzu-official": (True, "404", "2024-03 与任天堂和解后删库"),
    "citron-emulator": (True, "", "上游停更"),
    "strato-emulator": (True, "", "上游停更"),
}

# status 由 TAG_OVERRIDE 决定，但 status_of() 会先看 dead/repo_status。
# 对「本体已死但社区分支可用」的条目，显式给出展示用 status。
STATUS = {
    "skyline-pine": "active",
    "skyline-enhanced-infinity": "archived",
    "skyline-tagline": "community",
    "yuzu-citrus": "community",
    "yuzu-plus": "community",
    "ryujinx-benji-sc": "active",
    "kenji-nx-android-releases": "active",
    "ryubing-emulator": "active",
    "torzu-emulator": "stale",
    "eclipseds-nds": "community",
    "skyline-official": "archived",
    "ovsky-sumi-emu": "dead",
    "ryujinx-official": "dead",
    "drastic": "dead",
    "producdevity-gamehub": "active",
    "winlator-releases": "stale",
    "m64plusfz": "active",
}


def main():
    with open(REG, encoding="utf-8") as f:
        reg = json.load(f)

    touched = []
    for e in reg["emulators"]:
        eid = e.get("id")
        if eid in STATE:
            dead, rs, reason = STATE[eid]
            if dead:
                e["dead"] = True
                e["dead_reason"] = reason
            else:
                e.pop("dead", None)
                e.pop("dead_reason", None)
            if rs:
                e["repo_status"] = rs
            else:
                e.pop("repo_status", None)
            touched.append(eid)
        if eid in STATUS:
            e["status"] = STATUS[eid]

    with open(REG, "w", encoding="utf-8") as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"状态对齐 {len(touched)} 条")
    for t in touched:
        e = next(x for x in reg["emulators"] if x["id"] == t)
        print(f"  {t:32} dead={e.get('dead', False)!s:5} repo_status={e.get('repo_status','')!s:10} status={e.get('status')}")


if __name__ == "__main__":
    main()
