#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 platforms 的 65 种取值收敛成 8 个硬件家族，落到 platform_families 字段。

为什么要做：二级分类若按「厂商」手写 6 组，新增条目要人工归位；
按 platform 家族派生，新条目只要有 platforms 就自动落位。

约定：
  platform_families  只装硬件家族，供二级分类 chip 使用
  platforms          保持原样，保留到具体机型（ps2 不会被抹成 sony）
  非硬件值（multi / terminal / visual-novel / adventure）不进家族，
  因为它们描述的是「形态」或「题材」，不是「哪台机器」。
"""
import json

REG = "/tmp/eb/emulator-registry.json"

FAMILIES = {
    "sony": ("索尼系", {
        "ps1", "ps2", "ps3", "ps4", "ps5", "psp", "psvita", "pocketstation",
    }),
    "nintendo": ("任天堂系", {
        "nes", "snes", "n64", "gamecube", "wii", "wiiu", "switch", "switch2",
        "gb", "gbc", "gba", "nds", "3ds", "virtual-boy",
    }),
    "microsoft": ("微软系", {"xbox", "xbox360"}),
    "sega": ("世嘉系", {
        "dreamcast", "saturn", "mega-drive", "master-system", "game-gear",
        "sg-1000", "model3",
    }),
    # 街机与冷门掌机合并（设计稿「街机与掌机」）：单条太薄，合起来才有二级意义
    "arcade-handheld": ("街机与掌机", {
        "arcade", "neo-geo", "neo-geo-pocket", "wonderswan", "pc-engine",
        "atari-2600", "atari-jaguar", "atari-lynx", "colecovision",
    }),
    "retro-computer": ("复古电脑", {
        "dos", "amiga", "c64", "msx", "apple2", "pc-98", "zx-spectrum",
        "atari-st", "pc-6001", "amstrad-cpc", "philips-p2000", "nc2000",
        "pico-8",
    }),
    "mobile-legacy": ("老移动系统", {
        "java-me", "symbian", "brew", "windows-mobile", "zeebo",
    }),
    "pc": ("PC 与移动端", {"windows-pc", "ios", "web"}),
}

# 显式声明不收进家族的取值：形态/题材，不是硬件
NON_PLATFORM = {
    "multi": "多合一模拟器（形态）",
    "terminal": "终端宿主（host 概念）",
    "visual-novel": "视觉小说（题材）",
    "adventure": "冒险游戏（题材）",
}

P2F = {p: fam for fam, (_, ps) in FAMILIES.items() for p in ps}


def main():
    with open(REG, encoding="utf-8") as f:
        d = json.load(f)

    unknown = set()
    stat = {k: 0 for k in FAMILIES}
    multi_family = 0
    skipped = 0

    for e in d["emulators"]:
        fams = []
        for p in (e.get("platforms") or []):
            fam = P2F.get(p)
            if fam:
                if fam not in fams:
                    fams.append(fam)
            elif p in NON_PLATFORM:
                skipped += 1
            else:
                unknown.add(p)
        e["platform_families"] = fams
        if len(fams) > 1:
            multi_family += 1
        for f in fams:
            stat[f] += 1

    if unknown:
        print("!! 未映射的平台值：", sorted(unknown))
        raise SystemExit(1)

    with open(REG, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"已写入 {len(d['emulators'])} 条 platform_families")
    print(f"{'家族':<22}{'键':<18}{'条目数'}")
    for k, (label, _) in FAMILIES.items():
        print(f"{label:<22}{k:<18}{stat[k]}")
    print(f"\n跨家族条目（一条覆盖多家族）：{multi_family}")
    print(f"非硬件值跳过：{skipped} 处 -> {list(NON_PLATFORM)}")
    empty = sum(1 for e in d["emulators"] if not e["platform_families"])
    print(f"platform_families 为空的条目：{empty}")


if __name__ == "__main__":
    main()
