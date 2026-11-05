#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 62 种手写 `category` 收敛成「6 个分区 + 家族」，并把 family 落库。

问题：`category` 是人手填的自由文本，62 种取值混了三个维度——
  "Nintendo Switch Emulator"（平台）、"Winlator"（产品家族）、
  "Game Port"（形态）、"桌面模拟器"（宿主）、"GAME"（根本不是模拟器）。
同一个东西有 5 种写法：`Emulator PS2` / `PlayStation 2` / `PlayStation 2 Emulator`。

改法：
  section   6 个封闭值，从 method/kind 推导（不手填），决定 UI 一级分区
  family    上游代码谱系 id，从仓库名/项目名推导，决定排行归并（fork 不单独占榜位）
  category  降级为 section 的中文标签，保留兼容，不再手写

判据全部可审计：每条写出 `_section_evidence` / `_family_evidence`，
冲突项（老 category 与新 section 明显矛盾）单独列出，交人工。

用法：
  python3 scripts/normalize_category.py --dry-run   # 只看报告
  python3 scripts/normalize_category.py --apply     # 写回 registry
"""
import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
AUDIT = os.path.join(ROOT, "schema-audit.json")
OVERRIDES = os.path.join(ROOT, "family-overrides.json")

# ── 1. 分区（封闭词汇，6 个）─────────────────────────────────────────
# 优先级从上到下，第一条命中即定。判据只用 method / kind，不用自由文本。
SECTIONS = {
    "frontend-tool": "前端与工具",
    "virtualization": "虚拟机",
    "vn-engine": "视觉小说引擎",
    "windows-compat": "Windows 兼容层",
    "native-port": "原生移植",
    "console-emulation": "主机模拟",
}

# kind 为这些值时，无论 method 是什么都进「前端与工具」
TOOL_KINDS = {"frontend", "tool", "driver"}


def section_of(e):
    """返回 (section_key, evidence)。evidence 说明是哪条规则判的。"""
    kind = (e.get("kind") or "").strip()
    method = (e.get("method") or "").strip()

    if kind in TOOL_KINDS:
        # 例外：移植工具（nx2android / NX2Apple 这类把游戏重编译成原生 APK / APP 的）
        # 本质是「原生移植」的实现手段。按 kind 归进「前端与工具」会和驱动、
        # 文件管理器混在一起，用户按「在手机上跑 Switch 游戏」永远找不到。
        if method == "recomp":
            return "native-port", f"kind={kind} 但 method=recomp（移植工具）"
        return "frontend-tool", f"kind={kind}"
    if method == "virtualization":
        return "virtualization", "method=virtualization"
    if method == "engine":
        return "vn-engine", "method=engine"
    # 注意顺序：先判 emulation 再判 kind=port。
    # 反例：Swordpdf/ProsperoEden 是「把 Eden 移植到 PS5」，kind 记成 port，
    # 但它是模拟器不是游戏移植，归「原生移植」是错的。真正的游戏移植
    # （recomp 那批）method 都是 recomp，不会被这条吞掉。
    if method == "emulation":
        return "console-emulation", "method=emulation"
    if method == "recomp":
        return "native-port", "method=recomp"
    if kind == "port":
        return "native-port", f"kind=port（method={method or '空'}）"
    if method == "translation":
        return "windows-compat", f"method=translation（guest={','.join(e.get('platforms') or [])}）"
    # method 为空：只有能确认是终端的才放过，其余交人工
    plats = e.get("platforms") or []
    if plats and plats[0] == "terminal":
        return "frontend-tool", "method 空，platforms=[terminal]"
    return "console-emulation", "method 空，按默认归主机模拟（待核）"


# ── 2. 家族（上游谱系）───────────────────────────────────────────────
# 只在 `id + repo + name` 里出现上游名时才认族——描述里提到别人是常事，不可信。
# 这样保守，宁可漏认（fork 自成一族），不会错并（两个无关项目合并）。
FAMILY_RULES = [
    ("winlator", "Winlator 系", [r"winlator"]),
    ("gamehub", "GameHub 系", [r"gamehub"]),
    # BannerHub 是 the412banner 基于 GameHub 的自有发行版，名字里没有 gamehub，
    # 不单独建族会被拆成 4 条各自占榜位（实测 4 条同源）。
    ("bannerhub", "BannerHub 系", [r"bannerhub", r"bannerlator"]),
    ("gamenative", "GameNative 系", [r"gamenative"]),
    ("winnative", "WinNative 系", [r"winnative"]),
    # "prosperoeden-ps5" 里的 eden 是连写，不是 Eden 模拟器——必须卡左边界
    ("eden", "Eden 系", [r"(?<![a-z])eden(?![a-z])"]),
    ("yuzu", "yuzu 系", [r"\byuzu\b", r"yuzu-"]),
    ("sudachi", "Sudachi 系", [r"sudachi"]),
    ("ryujinx", "Ryujinx 系", [r"ryujinx", r"ryubing"]),
    ("skyline", "Skyline 系", [r"skyline"]),
    ("citra", "Citra 系", [r"citra"]),
    ("azahar", "Azahar 系", [r"azahar"]),
    ("lime3ds", "Lime3DS 系", [r"lime3ds"]),
    # WatermelonDS 是 SlaX 独立实现，不是 melonDS 的 fork，不能并族
    ("melonds", "melonDS 系", [r"(?<!water)melonds"]),
    ("dolphin", "Dolphin 系", [r"dolphin"]),
    ("pcsx2", "PCSX2 系", [r"pcsx2", r"nethersx2", r"armsx2"]),
    ("ppsspp", "PPSSPP 系", [r"ppsspp"]),
    ("rpcs3", "RPCS3 系", [r"rpcs3", r"armsx3"]),
    ("cemu", "Cemu 系", [r"cemu"]),
    ("vita3k", "Vita3K 系", [r"vita3k"]),
    ("mupen64plus", "Mupen64Plus 系", [r"mupen64plus", r"m64plus"]),
    ("duckstation", "DuckStation 系", [r"duckstation"]),
    ("retroarch", "RetroArch 系", [r"retroarch"]),
    ("termux", "Termux 系", [r"termux"]),
    ("xendroid", "XenDroid 系", [r"xendroid"]),
    ("flycast", "Flycast 系", [r"flycast"]),
    ("ekA2l1", "EKA2L1 系", [r"eka2l1"]),
    ("scummvm", "ScummVM 系", [r"scummvm"]),
    ("dosbox", "DOSBox 系", [r"dosbox"]),
]


def family_of(e, overrides):
    """返回 (family_key, family_label, evidence)。没有上游证据时自成一族。"""
    eid = e.get("id") or ""
    if eid in overrides:
        o = overrides[eid]
        return o["family"], o["label"], f"family-overrides.json: {o.get('why', '人工指定')}"

    # 只取仓库名（去掉 owner）：ARMSX2/ARMSX1 里 owner 叫 ARMSX2，用全路径会让
    # PS1 模拟器被并进 PCSX2 家族——实测踩过。
    repo_leaf = str(e.get("repo") or "").split("/")[-1]
    primary = " ".join([str(e.get("name") or ""), repo_leaf]).lower()
    fallback = " ".join([eid, primary]).lower()

    # 两遍匹配：先看名称+仓库名，没命中再用 id。id 是 owner-repo 拼的，含 owner
    # 前缀，混在一起会让 ARMSX2/ARMSX3 被 owner 的 "armsx2" 吞掉（实测踩过）。
    for hay, tag in ((primary, "名称/仓库"), (fallback, "id 兜底")):
        for key, label, pats in FAMILY_RULES:
            for p in pats:
                if re.search(p, hay):
                    return key, label, f"{tag}命中 /{p}/"
    # 自成一族
    return eid, e.get("name") or eid, "无上游证据，自成一族"


# ── 3. 冲突检测：老 category 说了平台，新 section 说是兼容层 ──────────
CONSOLE_CAT_HINTS = (
    "switch", "3ds", "ds", "ps1", "ps2", "ps3", "ps4", "ps5", "playstation",
    "xbox", "gamecube", "wii", "dreamcast", "saturn", "n64", "nintendo", "gba",
    "game boy", "snes", "nes", "atari", "arcade", "psp", "vita", "msx", "amiga",
    "pc-98", "zeebo", "j2me", "java", "symbian", "brew", "pico", "zx spectrum",
    "c64", "commodore", "mega drive", "handheld", "ios",
)


def conflict(e, sec):
    """老 category 明显在说主机平台，新 section 却不是主机模拟 → 可疑。"""
    old = (e.get("category") or "").lower()
    if sec == "console-emulation":
        return None
    if any(h in old for h in CONSOLE_CAT_HINTS):
        return f"老 category「{e.get('category')}」像平台，新分区「{SECTIONS[sec]}」"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if not args.apply and not args.dry_run:
        ap.error("要么 --dry-run，要么 --apply")

    with open(REG, encoding="utf-8") as f:
        data = json.load(f)
    emus = data["emulators"]

    overrides = {}
    if os.path.exists(OVERRIDES):
        with open(OVERRIDES, encoding="utf-8") as f:
            overrides = json.load(f)

    sec_stat = Counter()
    fam_members = defaultdict(list)
    conflicts, unknowns = [], []

    for e in emus:
        sec, sev = section_of(e)
        fam, flabel, fev = family_of(e, overrides)

        sec_stat[sec] += 1
        fam_members[fam].append(e)

        c = conflict(e, sec)
        if c:
            conflicts.append((e["id"], e.get("category"), SECTIONS[sec], c))
        if sev.endswith("待核"):
            unknowns.append(e["id"])

        e["section"] = sec
        e["category"] = SECTIONS[sec]          # 兼容字段，改为派生值
        e["family"] = fam
        e["family_label"] = flabel
        e["_section_evidence"] = sev
        e["_family_evidence"] = fev

    # 家族统计
    multi = {k: v for k, v in fam_members.items() if len(v) > 1}

    # 疑似误并：同族成员的目标平台完全不相交 → 很可能只是名字撞了
    suspicious = []
    for k, v in multi.items():
        sets = [set(m.get("platforms") or []) for m in v]
        inter = set.intersection(*sets) if sets else set()
        if not inter:
            suspicious.append((k, [m["id"] for m in v], [sorted(s) for s in sets]))

    print("=" * 68)
    print(f"条目 {len(emus)} 条 → 分区 {len(sec_stat)} 个")
    print("=" * 68)
    for k, n in sec_stat.most_common():
        print(f"  {SECTIONS[k]:<14s}{n:>4d}")
    print()
    print(f"识别出 {len(multi)} 个多成员家族（共 {sum(len(v) for v in multi.values())} 条）：")
    for k, v in sorted(multi.items(), key=lambda x: -len(x[1])):
        tops = sorted(v, key=lambda x: -(x.get("downloads") or 0))[:3]
        print(f"  {k:<12s}{len(v):>3d} 条  e.g. " + ", ".join(t["id"] for t in tops))
    solo = len(emus) - sum(len(v) for v in multi.values())
    print(f"  （其余 {solo} 条自成一族）")
    print()
    if suspicious:
        print(f"⚠ 疑似误并（同族但目标平台零交集）{len(suspicious)} 组，必须人工看一眼：")
        for k, ids, ps in suspicious:
            print(f"  {k}: {ids}")
            print(f"      platforms={ps}")
        print()
    if conflicts:
        print(f"⚠ 分区与老 category 冲突 {len(conflicts)} 条，建议人工核：")
        for c in conflicts:
            print(f"  {c[0]:<42s}{c[1]:<24s}→ {c[2]}")
        print()
    if unknowns:
        print(f"⚠ method 为空、按默认分区的 {len(unknowns)} 条：{', '.join(unknowns)}")

    audit = {
        "generated_by": "scripts/normalize_category.py",
        "sections": {k: {"label": v, "count": sec_stat[k]} for k, v in SECTIONS.items()},
        "families": {
            k: {"label": fam_members[k][0].get("family_label"), "members": [m["id"] for m in v]}
            for k, v in sorted(multi.items())
        },
        "conflicts": [{"id": a, "old_category": b, "new_section": c, "why": d} for a, b, c, d in conflicts],
        "suspicious_family_merges": [
            {"family": k, "members": ids, "platforms": ps} for k, ids, ps in suspicious
        ],
        "method_missing": unknowns,
    }
    with open(AUDIT, "w", encoding="utf-8") as f:
        json.dump(audit, f, ensure_ascii=False, indent=2)
    print(f"\n审计写入 {AUDIT}")

    if args.apply:
        data["version"] = "1.3.0"
        with open(REG, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"已写回 {REG}（{len(emus)} 条）")
    else:
        print("（dry-run，未写回）")


if __name__ == "__main__":
    main()
