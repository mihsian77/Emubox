#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出缺口总表 Markdown 版（与 HTML 看板同源同分组）。"""
import json
from datetime import datetime

rows = json.load(open("/tmp/rows.json", encoding="utf-8"))
reg = json.load(open("emulator-registry.json", encoding="utf-8"))
TOTAL = len(reg["emulators"])

PLAT = {
    "windows-pc": "Windows", "switch": "Switch", "n64": "N64", "xbox360": "Xbox360",
    "ps1": "PS1", "gba": "GBA", "gb": "GB", "multi": "多平台", "nds": "NDS",
    "ps3": "PS3", "ios": "iOS", "3ds": "3DS", "ps4": "PS4", "dreamcast": "DC",
    "arcade": "街机", "java-me": "J2ME", "atari-jaguar": "Jaguar", "atari": "Atari",
    "pico-8": "PICO-8", "brew": "BREW", "gbc": "GBC", "psp": "PSP",
    "gamecube": "NGC", "wii": "Wii", "ps5": "PS5", "mega-drive": "MD",
    "ps2": "PS2", "snes": "SFC",
}
CLOSED_SRC = {"official-play", "official-web", "official-site", "official-store", "official-apk"}


def has(r, *k):
    return any(x in r["gaps"] for x in k)


def is_ns(r):
    return has(r, "no_repo_field", "unresolvable", "repo_451")


def is_closed(r):
    if not is_ns(r) or has(r, "repo_451"):
        return False
    if r.get("source") in CLOSED_SRC:
        return True
    h = (r.get("home") or "").lower()
    return "play.google.com" in h or "apps.apple.com" in h


G = [
    ("A", "查不到任何一手来源", lambda r: is_ns(r) and not is_closed(r)),
    ("B", "仓库地址失效（404）", lambda r: has(r, "repo_404") and not is_ns(r)),
    ("C", "有仓库但零 Release", lambda r: has(r, "no_release") and not has(r, "no_apk_anywhere_in_releases")),
    ("D", "有 Release 但资产里没 APK", lambda r: has(r, "no_apk_anywhere_in_releases")),
    ("E", "版本记录过期", lambda r: has(r, "tag_stale", "tag_missing")),
    ("F", "仓库已归档（停更）", lambda r: has(r, "archived")),
    ("G", "缺图标 / 缺简介", lambda r: has(r, "no_logo", "no_description")),
    ("H", "闭源：只有商店或官网（非缺口）", lambda r: is_closed(r)),
]
used = set()
for c, _t, p in G:
    for r in rows:
        if r["id"] not in used and p(r):
            r["group"] = c
            used.add(r["id"])
for r in rows:
    if r["id"] not in used:
        r["group"] = "D"

by = {c: [] for c, _t, _p in G}
for r in rows:
    by[r["group"]].append(r)
for v in by.values():
    v.sort(key=lambda r: -(r.get("stars") or 0))


def fact(r):
    p = []
    if r.get("stars") is not None:
        p.append(f"★{r['stars']}")
    if r.get("pushed"):
        p.append(f"最后提交 {r['pushed']}")
    if r.get("releases") is not None:
        p.append(f"Release {r['releases']} · APK {r.get('apk') or 0} · 其他包 {r.get('pkg') or 0}")
    if r.get("sample"):
        p.append(f"`{r['sample'][:40]}`")
    if r.get("archived"):
        p.append("已归档")
    if r.get("note"):
        p.append(r["note"][:60])
    return "；".join(p) if p else "—"


out = [f"# EmuBox 项目库缺口总表\n",
       f"> 生成 {datetime.now().strftime('%Y-%m-%d %H:%M')}｜库内 {TOTAL} 条，有缺口 {len(rows)} 条，四项齐全 {TOTAL - len(rows)} 条｜全表零过滤\n"]

for c, title, _p in G:
    items = by[c]
    if not items:
        continue
    out.append(f"\n## {c} 类 · {title}（{len(items)} 条）\n")
    out.append("| # | 名称 | 平台 | 分类 | 已核实的事实 | 缺口 |\n|---|---|---|---|---|---|")
    for i, r in enumerate(items, 1):
        pl = "/".join(PLAT.get(x, x) for x in (r.get("plat") or [])[:3]) or "—"
        gaps = "/".join(dict.fromkeys(r["gaps"]))
        out.append(f"| {i} | {r['name']} | {pl} | {r['cat']} | {fact(r)} | {gaps} |")

open("/workspace/项目库缺口总表.md", "w", encoding="utf-8").write("\n".join(out) + "\n")
print("ok", len(rows), {c: len(v) for c, v in by.items()})
