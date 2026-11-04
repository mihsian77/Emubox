#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""高置信度缺口回填：只补审计里明确标记为「可恢复」的字段。

与 apply_fixes.py 的区别：
  apply_fixes 会用「最新 release」全面覆盖 latest_tag / latest_apk_url /
  distribution，这会冲掉社区改名包等条目的人工标注（那些条目的 release
  tag 未必是有效版本，如 nightly / preview / moved 之类滚动 tag）。
  本脚本只做三件审计已明确背书的事：
    1. apk_recoverable   —— release 里确实有 APK，但 registry 没记直链 → 回填
    2. homepage_recoverable —— 仓库 meta 有 homepage 而 registry 没记 → 回填
    3. latest_tag 过期且最新 release 是「带 APK 的正式版」→ 同步 tag 与 APK
其余一律不动。
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
AUDIT = os.path.join(ROOT, "audit-report.json")

# 人工标注过的条目不参与自动覆盖（社区改名包 / 溯源修正过的结论）
PROTECTED = {
    "eclipseds-nds", "ryujinx-benji-sc", "skyline-pine", "skyline-enhanced-infinity",
    "skyline-tagline", "yuzu-citrus", "yuzu-plus", "skyline-official",
    "torzu-emulator", "kenji-nx-android-releases", "ryujinx-official",
    "ovsky-sumi-emu", "drastic", "myps2-ps2", "pizzaboy", "myboy", "myoldboy",
    "m64plusfz", "epsxe", "fpse", "producdevity-gamehub", "sudachi-emulator",
    "termux-jiaxinchen", "termux-plus", "termux-enhanced", "termux-aether",
    "termux-zero", "termux-kotlin", "winlator-releases", "ryubing-emulator",
    "ryubing-canary", "melonx-ios", "ruzu-emu", "eden-nx2-zuan",
    "prospero-eden-encore", "dosbox-staging", "dosbox-x", "dosbox-pure",
    "flycast-dojo", "snes9x-z", "mupen64plus-ae-fzurita", "ryudeck-steamdeck",
    "ryujinx-canary-ademola",
}

# 滚动 / 无意义 tag，不作为版本号
ROLLING_TAG = re.compile(
    r"^(latest|previews?|nightly.*|dev.*|moved|stable|preview|canary.*|"
    r"yesys.*|.*_support|.*-link)$", re.I)


def main():
    reg = json.load(open(REG, encoding="utf-8"))
    with open(AUDIT, encoding="utf-8") as f:
        audit = json.load(f)
    amap = {x["id"]: x for x in audit["items"]}

    changed = []
    for e in reg["emulators"]:
        eid = e.get("id")
        if eid in PROTECTED:
            continue
        a = amap.get(eid)
        if not a:
            continue
        gaps = a.get("gaps", [])
        probe = a.get("probe", {})
        upd = {}

        if "apk_recoverable" in gaps:
            s = probe.get("apk_sample")
            if s and s.get("url"):
                upd.update({
                    "latest_apk_name": s["name"],
                    "latest_apk_url": s["url"],
                    "latest_apk_size": s.get("size", 0),
                    "latest_date": (s.get("date") or "") + "T00:00:00Z",
                    "distribution": "release-apk",
                })
                if s.get("tag") and not ROLLING_TAG.match(s["tag"]):
                    upd["latest_tag"] = s["tag"]

        if "homepage_recoverable" in gaps:
            hp = probe.get("homepage_from_meta")
            if hp:
                upd["homepage"] = hp

        # 版本过期：只在最新 release 有 APK 且 tag 不是滚动 tag 时才同步
        stale = [g for g in gaps if g.startswith("tag_stale:")]
        if stale and "apk_recoverable" not in gaps:
            real = probe.get("latest_release_tag") or ""
            s = probe.get("apk_sample")
            if real and not ROLLING_TAG.match(real) and s and s.get("url"):
                upd.update({
                    "latest_tag": real,
                    "latest_apk_name": s["name"],
                    "latest_apk_url": s["url"],
                    "latest_apk_size": s.get("size", 0),
                    "latest_date": (s.get("date") or "") + "T00:00:00Z",
                })

        if upd:
            before = json.dumps(e, ensure_ascii=False, sort_keys=True)
            for k, v in upd.items():
                if v not in (None, ""):
                    e[k] = v
            if json.dumps(e, ensure_ascii=False, sort_keys=True) != before:
                changed.append(eid)

    if "--dry" in sys.argv:
        print(f"将改动 {len(changed)} 条: {', '.join(changed)}")
        return

    with open(REG, "w", encoding="utf-8") as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"回填 {len(changed)} 条: {', '.join(changed)}")


if __name__ == "__main__":
    main()
