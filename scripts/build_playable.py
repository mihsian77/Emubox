#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 playable-matrix.json：把「本机芯片」换算成「哪些平台跑得动」。

为什么单独一张表，而不是并进 soc_master.json：
  soc_master 的主键是部件号、职责是「这颗芯片是什么」；可玩性是「这颗芯片能干什么」，
  两者的更新频率和出错后果完全不同——平台门槛会随模拟器成熟度调整，芯片规格不会。
  分开之后调门槛不用重跑 build_master，也不会碰到 389 条规格数据。

分数口径：
  socpk 极客湾「手机芯片 GPU 性能排行」，骁龙 865 = 100 为基准。
  模拟器吃 GPU 远多于吃 CPU，所以用 GPU 分而不是综合分。
  132 颗可直接按名字命中；其余用安兔兔 GPU 分的**百分位**映射到同一分布
  （线性换算误差最大到 60%，低端芯片尤其不准，而低端正是「能不能跑」的临界区，
   所以只借排序不借绝对值），产物里标 src=antutu，界面显示要带「≈」。

平台门槛是经验值，不是实测：
  依据是各模拟器在骁龙 865 / 855 / 730G 三级设备上的社区反馈，
  取「多数游戏 1x 分辨率稳定」为流畅线，「能进游戏但掉帧/需降分辨率」为可玩线。
  改门槛只改下面的 PLATFORMS，重跑本脚本即可，不涉及代码。
"""
import csv
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOC_CSV = os.path.join(ROOT, "scripts", "soc", "data", "socpk-chip-gpu.csv")
MASTER = os.path.join(ROOT, "app", "src", "main", "assets", "soc_master.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "playable-matrix.json")

# 平台门槛：smooth=多数游戏稳定 1x 的 GPU 分下限；ok=能进游戏、需降分辨率或接受掉帧。
# minRamMb 是额外硬条件（内存不够时 GPU 再强也会被系统杀进程）。
# match 是该档对应的模拟器数据 platforms 值，用于「这个平台库里有几个能装的」计数与跳转筛选。
PLATFORMS = [
    # key, 展示名, smooth, ok, minRam, 备注, match
    ("ps2", "PlayStation 2", 120, 85, 4096, "AetherSX2 系；2x 分辨率需再上一档", ["ps2"]),
    ("gamecube", "GameCube", 65, 42, 3072, "Dolphin 系，1x 基本满帧", ["gamecube"]),
    ("wii", "Wii", 70, 46, 3072, "与 GameCube 同源，动作游戏略吃 GPU", ["wii"]),
    ("3ds", "Nintendo 3DS", 60, 40, 3072, "双屏渲染，实际帧率受 CPU 影响更大", ["3ds"]),
    ("psvita", "PS Vita", 120, 80, 4096, "Vita3K 仍在早期，兼容性比帧率更成问题", ["psvita"]),
    ("switch", "Nintendo Switch", 600, 350, 6144, "需 8 Gen 1 级别以上，且强依赖散热", ["switch"]),
    ("wiiu", "Wii U", 650, 420, 6144, "Cemu 系安卓移植，仅少数游戏可进", ["wiiu"]),
    ("psp", "PSP", 28, 16, 2048, "PPSSPP 2x 分辨率也很轻松", ["psp"]),
    ("dreamcast", "Dreamcast", 22, 13, 2048, "Flycast 系", ["dreamcast"]),
    ("saturn", "Sega Saturn", 15, 9, 2048, "Yaba Sanshiro 系", ["saturn"]),
    ("n64", "Nintendo 64", 12, 7, 2048, "Mupen64Plus / RetroArch 核心", ["n64"]),
    ("nds", "Nintendo DS", 5, 3, 1536, "2D 为主，近十年芯片全部满帧", ["nds"]),
    ("ps1", "PlayStation", 8, 4, 1536, "DuckStation 系，2x 内部分辨率无压力", ["ps1"]),
    ("classic", "8/16 位机与街机", 4, 2, 1024, "GBA / SFC / MD / 街机 / MSX 等", [
        "gb", "gbc", "gba", "snes", "nes", "mega-drive", "master-system", "game-gear",
        "arcade", "msx", "pc-engine", "neo-geo", "wonderswan", "atari-2600",
        "colecovision", "sg-1000", "virtual-boy", "atari-lynx", "neo-geo-pocket",
        "c64", "zx-spectrum", "amstrad-cpc", "atari-st", "pc-98", "pc-6001",
        "pico-8", "apple2", "vectrex", "intellivision",
    ]),
    ("pc", "Windows 游戏（Wine 转译）", 300, 120, 6144, "还受 CPU 单核与转译层限制，GPU 只是门槛之一",
     ["windows-pc", "dos"]),
]

# 不建议给分数的平台：安卓端能装，但只能跑极个别游戏，给「流畅/勉强」是误导。
EXPERIMENTAL = [
    ("ps3", "PlayStation 3", "RPCS3 系安卓移植，目前仅演示级"),
    ("xbox360", "Xbox 360", "Xenia 系安卓移植，兼容性极低"),
    ("ps4", "PlayStation 4", "shadPS4 系，仅少数独立游戏可进"),
    ("ps5", "PlayStation 5", "仅有移植工具，无通用模拟能力"),
    ("switch2", "Nintendo Switch 2", "尚无可用模拟器"),
]


BRANDS = (
    ("mtk", ("天玑", "dimensity", "mediatek", "联发科", "helio", "曦力")),
    ("qc", ("骁龙", "snapdragon", "qualcomm", "高通")),
    ("hw", ("麒麟", "kirin", "海思")),
    ("ss", ("exynos", "三星")),
    ("google", ("tensor", "google")),
    ("unisoc", ("紫光", "unisoc", "展锐", "spreadtrum")),
    ("apple", ("apple", "苹果")),
)

# 同型号的性能修饰后缀：天玑 8400 与 8400-MAX / -Ultra / 满血版 是同一颗芯片的不同频率版本，
# 极客湾只测了其中部分变体，缺分的可以借同族的分（界面标「同族」）。
SUFFIX = ("max", "ultra", "plus", "pro", "turbo", "ultimate", "满血版", "apex",
          "extreme", "super", "oc", "增强版", "领先版", "至尊版", "ultimate")


def brand_of(s):
    low = (s or "").lower()
    for key, words in BRANDS:
        if any(w in low for w in words):
            return key
    return "other"


def norm(s):
    """归一化芯片名：去括号内容、去厂商前缀、去空格与连字符。"""
    s = (s or "").lower()
    s = re.sub(r"[（(].*?[)）]", "", s)
    for a in ("骁龙", "snapdragon", "天玑", "dimensity", "麒麟", "kirin",
              "exynos", "三星", "google", "tensor", "helio", "曦力", "联发科",
              "mediatek", "紫光", "unisoc", "展锐"):
        s = s.replace(a, "")
    return s.replace(" ", "").replace("-", "").replace("_", "")


def family_key(s):
    """型号主干：品牌 + 去掉性能修饰后缀的型号。

    必须带品牌——「麒麟 650」和「骁龙 650」归一化后都是 650，
    只按型号聚合会把华为的芯片套到高通的跑分上。
    """
    x = norm(s)
    x = x.rstrip("+")
    prev = None
    while prev != x:
        prev = x
        for suf in SUFFIX:
            if x.endswith(suf) and len(x) > len(suf):
                x = x[: -len(suf)]
                break
    # 数值型号末尾的单字母是频率/GPU 提频标识（骁龙 730 与 730G、天玑 1000 与 1000C），
    # 视为同族；非数值型号（如 A18 Pro）不动，A18 和 A18 Pro 本来就是两颗。
    if re.fullmatch(r"[a-z]*\d+[a-z]", x):
        x = x[:-1]
    return brand_of(s) + ":" + x.rstrip("+")


def load_socpk():
    rows = []
    with open(SOC_CSV, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r.get("gpu_score"):
                rows.append((r["name"], float(r["gpu_score"])))
    return rows


def monotonic_curve(pairs):
    """把 (antutu, socpk) 共同样本拟合成单调曲线，返回 (xs, ys)。

    安兔兔 GPU 分与极客湾 GPU 分不可线性换算：线性拟合在低端段误差达 60%。
    这里用保序回归（PAVA）先强制「安兔兔分越高、极客湾分不降低」，再分段线性插值。
    仍然存在的系统性偏差是：安兔兔 V10 对新架构给分偏松，同代中端新芯片会虚高，
    所以折算结果再乘 CONSERVATIVE 系数，宁可低估（判「勉强」）也不高估（判「流畅」）。
    """
    pts = sorted(pairs, key=lambda p: p[0])
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w = [1.0] * len(ys)
    blocks = [[i] for i in range(len(ys))]

    def block_val(b):
        return sum(w[i] * ys[i] for i in b) / sum(w[i] for i in b)

    i = 0
    while i < len(blocks) - 1:
        if block_val(blocks[i]) > block_val(blocks[i + 1]):
            blocks[i] += blocks[i + 1]
            blocks.pop(i + 1)
            if i > 0:
                i -= 1
        else:
            i += 1
    fit = [0.0] * len(ys)
    for b in blocks:
        v = block_val(b)
        for idx in b:
            fit[idx] = v
    return xs, fit


# 安兔兔折算的保守系数：跨代虚高约 15%，向下修正以免把「勉强」误判成「流畅」。
CONSERVATIVE = 0.85


def interpolate(xs, ys, x):
    import bisect
    j = bisect.bisect_left(xs, x)
    if j <= 0:
        return ys[0]
    if j >= len(xs):
        return ys[-1]
    x0, x1 = xs[j - 1], xs[j]
    y0, y1 = ys[j - 1], ys[j]
    if x1 == x0:
        return y0
    return y0 + (y1 - y0) * (x - x0) / (x1 - x0)


def main():
    socpk = load_socpk()
    master = json.load(open(MASTER, encoding="utf-8"))
    socs = master["socs"]

    # 名字索引 → soc id
    by_name = {}
    for s in socs:
        for n in [s.get("name"), s.get("name_en")] + (s.get("names") or []):
            if n:
                by_name.setdefault(norm(n), []).append(s)

    scores = {}
    matched_ids = set()
    for name, score in socpk:
        for s in by_name.get(norm(name), []):
            scores[s["id"]] = {"s": round(score, 1), "src": "socpk"}
            matched_ids.add(s["id"])

    # 第二层：同族借分。天玑 8400 本身没被极客湾收录，但 8400-MAX / -Ultra / 满血版都是同一颗芯片，
    # 取同族均值并标 src=family（界面显示「同族参考」），比按安兔兔折算可靠。
    fam = {}
    for s in socs:
        if s["id"] in scores:
            fam.setdefault(family_key(s["name"]), []).append(scores[s["id"]]["s"])
    family_used = 0
    for s in socs:
        if s["id"] in scores:
            continue
        vals = fam.get(family_key(s["name"]))
        if vals:
            scores[s["id"]] = {"s": round(sum(vals) / len(vals), 1), "src": "family"}
            family_used += 1

    # 第三层：用安兔兔 GPU 分折算（保序回归 + 保守系数）
    common = []
    for s in socs:
        if s["id"] in scores and scores[s["id"]]["src"] == "socpk" and s.get("antutu_gpu"):
            try:
                common.append((float(s["antutu_gpu"]), scores[s["id"]]["s"]))
            except (TypeError, ValueError):
                pass
    xs, ys = monotonic_curve(common)

    rest = [s for s in socs if s["id"] not in scores and s.get("antutu_gpu")]
    mapped = {}
    for s in rest:
        try:
            v = interpolate(xs, ys, float(s["antutu_gpu"]))
        except (TypeError, ValueError):
            continue
        mapped[s["id"]] = round(v * CONSERVATIVE, 1)
    for sid, v in mapped.items():
        scores[sid] = {"s": v, "src": "antutu"}

    payload = {
        "version": "1.0.0",
        "generated": "2026-11-13",
        "benchmark": {
            "name": "极客湾 手机芯片 GPU 性能排行",
            "base": "骁龙 865 = 100",
            "source": "socpk.com",
            "note": "模拟器主要吃 GPU。src=socpk 为极客湾实测；family 为同族变体均值；"
                    "antutu 为按安兔兔 GPU 分保序回归折算，已乘 0.85 保守系数，界面应显示约等于号",
        },
        "platforms": [
            {"key": k, "label": label, "smooth": sm, "ok": ok, "minRamMb": ram,
             "note": note, "match": match}
            for k, label, sm, ok, ram, note, match in PLATFORMS
        ],
        "experimental": [
            {"key": k, "label": label, "reason": reason} for k, label, reason in EXPERIMENTAL
        ],
        "scores": scores,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=0, sort_keys=True)
        f.write("\n")

    exact = sum(1 for v in scores.values() if v["src"] == "socpk")
    derived = sum(1 for v in scores.values() if v["src"] == "antutu")
    missing = [s["name"] for s in socs if s["id"] not in scores]
    print(f"写入 {OUT}")
    print(f"芯片 {len(socs)} 颗，有分数 {len(scores)} 颗"
          f"（实测 {exact} / 同族 {family_used} / 折算 {derived}），无分数 {len(missing)} 颗")
    print(f"src=antutu 的说明：{payload['benchmark']['note']}")
    if missing:
        print(f"无分数：{', '.join(missing[:10])}{' …' if len(missing) > 10 else ''}")


if __name__ == "__main__":
    main()
