#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给注册表补齐标签体系：kind / runtime / platforms / status。
标签是给用户看的小胶囊，与 category 正交：category 说明"这是什么品类"，
platforms 说明"跑什么游戏"，runtime 说明"在什么环境跑"，status 说明"还活着吗"。"""
import json, re, sys, time

REG = "emulator-registry.json"

# 品类关键词 -> 目标平台 slug
PLATFORM_MAP = [
    ("nintendo 64", "n64"), ("n64", "n64"),
    ("nintendo ds", "nds"),
    ("nintendo 3ds", "3ds"), ("3ds", "3ds"),
    ("game boy advance", "gba"),
    ("game boy color", "gbc"),
    ("game boy", "gb"),
    ("super nintendo", "snes"),
    ("nintendo entertainment", "nes"), (" nes", "nes"),
    ("playstation 1", "ps1"), ("ps1", "ps1"),
    ("playstation 2", "ps2"), ("ps2", "ps2"),
    ("playstation 3", "ps3"), ("ps3", "ps3"),
    ("playstation 4", "ps4"), ("ps4", "ps4"),
    ("playstation 5", "ps5"), ("ps5", "ps5"),
    ("nintendo switch", "switch"), ("switch", "switch"),
    ("gamecube", "gamecube"), ("/ wii", "wii"), ("wii", "wii"),
    ("arcade", "arcade"),
    ("saturn", "saturn"),
    ("dreamcast", "dreamcast"),
    ("lynx", "lynx"),
    ("wonderswan", "wonderswan"),
    ("neo geo", "neo-geo"),
    ("portable", "psp"), ("psp", "psp"),
    ("java", "java-me"),
    ("pico-8", "pico-8"),
    ("brew", "brew"),
    ("winlator", "windows-pc"),
    ("pc emulator", "windows-pc"),
    ("all in one", "multi"),
    ("gamehub", "windows-pc"),
    ("gamenative", "windows-pc"),
    ("xbox", "xbox360"),
    ("psvita", "psvita"),
    ("windows mobile", "windows-mobile"),
    ("windows pc", "windows-pc"),
    ("terminal", "terminal"),
    ("handheld", "multi"),
    ("ios emulator", "ios"),
    ("symbian", "symbian"),
    ("zeebo", "zeebo"),
    ("j2me", "java-me"),
    ("java emulator", "java-me"),
    ("mega drive", "mega-drive"),
    ("web/flash", "web"),
    ("atari", "atari"),
    ("sega model 3", "model3"),
    ("game", "windows-pc"),
]

# 逐条指定目标平台（category 推不出来的）
PLATFORM_FIX = {
    "zelda64recomp-zelda64recomp": ["n64"],
    "banjorecomp-banjorecomp": ["n64"],
    "rainchus-dk64-rekongpiled": ["n64"],
    "harbourmasters-shipwright": ["n64"],
    "2ship2harkinian-2ship2harkinian": ["n64"],
    "linkzenic-2ship2harkinian-android": ["n64"],
    "linkzenic-shipwright-ports": ["n64"],
    "waterdish-shipwright-android": ["n64"],
    "harbourmasters-spaghettikart": ["n64"],
    "banjorecomp-android": ["n64"],
    "8cee-banjorecomp-android": ["n64"],
    "frostie77-tooie-recompiled": ["n64"],
    "dk64recomp": ["n64"],
    "jackandbeans-snap64recomp": ["n64"],
    "mchughalex-skate3recomp": ["ps3"],
    "nfs3recompiled": ["ps3"],
    "felipeprado0-spartanport": ["xbox360"],
    "supermedo-bloodborne-pc": ["ps4"],
    "shadps4-emu-shadps4": ["ps4"],
    "kytyps5-kytyps5": ["ps5"],
    "silksongandroid": ["ps5"],
    "prosperoeden-ps5": ["ps5"],
    "anyps5-porting-tool": ["ps5"],
    "harbourmasters-ghostship": ["n64"],
    "khmelonmix": ["gba"],
    "metroidprimeport": ["gamecube"],
    "opennectar": ["gamecube"],
    "unleashedrecomp": ["gamecube"],
    "twilitrealm-dusklight": ["multi"],
    "zorkats-g-diffuser": ["multi"],
    "mamcjr-botwrecompandroid": ["switch"],
    "mamcjr-totkdroid": ["switch"],
    "shadps4-android": ["ps4"],
    "bloodborne-android-rebuild": ["ps4"],
    "shipwright-android": ["n64"],
    "pocket8": ["pico-8"],
    "jaycore-mobox-patched": ["windows-pc"],
    "melange-brew": ["brew"],
    "rakuen-cocktail": ["multi"],
    "silent-hill-port": ["ps2"],
    "supermodel3": ["model3"],
    "virtualjaguar": ["atari-jaguar"],
    "raport-redalert": ["windows-pc"],
    "winlatorbrasil-exiled-kingdoms-multiplayer": ["windows-pc"],
    "touchhle": ["ios"],
    "hyperhle": ["ios"],
    "hyperhle-reborn": ["ios"],
    "eka2l1": ["symbian"],
    "afeimod-eka2l1-an": ["symbian"],
    "afeimod-eden-an": ["symbian"],
    "zeebx-zeebo": ["zeebo"],
    "wie-skvm": ["java-me"],
    "md.emu-sms": ["mega-drive"],
    "afeimod-gamehtml": ["web"],
    "emucorep-android": ["windows-pc"],
    "emucorev-android": ["psvita"],
    "wpr-wp7-8": ["windows-mobile"],
    "pockethle-android": ["windows-mobile"],
    "pocket2droid-android": ["windows-mobile"],
    "dingooemu-android": ["multi"],
    "termux": ["terminal"], "termux-plus": ["terminal"], "termux-enhanced": ["terminal"],
    "termux-ghostty": ["terminal"], "termux-kotlin": ["terminal"], "termux-aether": ["terminal"],
    "termux-zero": ["terminal"], "termux-jiaxinchen": ["terminal"],
}

# runtime 判定
def runtime_of(e):
    dist = e.get("distribution") or ""
    cat = (e.get("category") or "").lower()
    dk = e.get("download_kind") or ""
    if dk == "termux":
        return "termux"
    if cat.startswith("pc 移植"):
        return "winlator"
    if cat == "桌面模拟器" or cat == "移植工具":
        return "desktop"
    if dist == "libretro" or "libretro" in (e.get("repo") or ""):
        return "libretro"
    if e.get("latest_apk_url"):
        return "android-native"
    if dk == "play" or dk == "official-web" or e.get("download_url"):
        return "android-native"
    if cat in ("pico-8 emulator",):
        return "android-native"
    return "desktop"


def kind_of(e):
    cat = (e.get("category") or "").lower()
    if "内核" in cat:
        return "kernel"
    if "工具" in cat:
        return "tool"
    if "移植" in cat or cat == "game port":
        return "port"
    if "前端" in cat or cat == "all in one":
        return "frontend"
    if "驱动" in cat or "组件" in cat:
        return "driver"
    if "模拟器" in cat or "emulator" in cat:
        return "emulator"
    if "移植" in cat:
        return "port"
    return "emulator"


def platforms_of(e):
    eid = e.get("id")
    if eid in PLATFORM_FIX:
        return PLATFORM_FIX[eid]
    cat = (e.get("category") or "").lower()
    name = (e.get("name") or "").lower()
    out = []
    for key, slug in PLATFORM_MAP:
        if key in cat and slug not in out:
            out.append(slug)
    if not out:
        for key, slug in PLATFORM_MAP:
            if key in name and slug not in out:
                out.append(slug)
    return out


def status_of(e):
    if e.get("dead"):
        return "dead"
    st = e.get("repo_status")
    if st in ("404", "451"):
        return "dead"
    if st == "archived":
        return "archived"
    if (e.get("distribution") or "") == "community-archive":
        return "community"
    p = e.get("pushed_at") or ""
    if p:
        try:
            from datetime import datetime, timezone
            dt = datetime.strptime(p[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
            age = (datetime.now(timezone.utc) - dt).days
            if age > 540:
                return "stale"
        except Exception:
            pass
    return "active"


def main():
    d = json.load(open(REG, encoding="utf-8"))
    n = 0
    for e in d.get("emulators", []):
        e["kind"] = kind_of(e)
        e["runtime"] = runtime_of(e)
        e["platforms"] = platforms_of(e)
        e["status"] = status_of(e)
        n += 1
    d["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    json.dump(d, open(REG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(REG, "a").write("\n")
    print(f"打标 {n} 条")


if __name__ == "__main__":
    main()
