#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按「全平台」口径补录 EmuCR 发现的库外项目。

来源：EmuCR 2026-11-01~04 的 100 条 release 公告 → 文章页抽 github 地址 → 与库比对，
39 个库外仓库中剔除 6 个非项目条目（二进制仓库/CI/发行包/GUI fork/夜间构建/启动器）
+ 2 个镜像 fork，剩 31 个，本脚本一次补入。

原则：
- 只补元数据与 latest release，不伪造下载量（downloads 留 null，downloads_basis=none）
- note 写明「为什么收」与「它和库里已有条目的关系」，避免以后重复收
- 不改动任何已有条目
"""
import json, re, sys, time, urllib.request
from datetime import datetime

REG = "/root/.emubox/Emubox/emulator-registry.json"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) emubox-batch"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r)


def iso(s):
    return (s or "")[:10] + "T00:00:00Z" if s else ""


def short(s, n=220):
    s = re.sub(r"\s+", " ", s or "").strip()
    return s[:n]


# repo -> (name, category, section, platforms, hosts, kind, runtime, description, note)
META = {
"par274/sharpemu": ("SharpEmu", "主机模拟", "console-emulation", ["ps5"], ["windows", "linux"], "emulator", "desktop",
  "实验性 PS5 模拟器（C#），面向 Windows/Linux，2026-03 建仓，11 月仍每日推送。",
  "EmuCR 11-01 公告带出仓库地址。此前库里 PS5 只有 Prospero/Eden 系，缺一个纯社区实验项目；它是目前 PS5 模拟器里星标最高的独立仓库。"),
"Vita3K/Vita3K": ("Vita3K", "主机模拟", "console-emulation", ["psvita"], ["windows", "linux", "macos"], "emulator", "desktop",
  "PS Vita 模拟器桌面版（Windows/Linux/macOS），Vita3K 项目的上游主仓库。",
  "库里此前只收了 Vita3K-Android（1664★），上游本体 5942★ 缺失——收了下游漏了上游，属于结构缺口。归入 vita3k 家族。"),
"xenia-canary/xenia-canary": ("Xenia Canary", "主机模拟", "console-emulation", ["xbox360"], ["windows"], "emulator", "desktop",
  "Xenia 的金丝雀构建线，比主线更激进的每日构建，兼容性试验优先。",
  "库里只有 xenia-project/xenia 主线；canary 是社区实际用来跑新游戏的构建线，长期漏收。归入 xenia 家族。"),
"trekawek/coffee-gb": ("Coffee GB", "主机模拟", "console-emulation", ["gb", "gbc"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Java 写的 Game Boy / Game Boy Color 精确模拟器，强调周期准确与测试覆盖。",
  "EmuCR 11-02 公告。库里 GB/GBC 只有 SameBoy/Gambatte 系，缺一个 Java 跨平台实现；1173★ 属高星遗漏。"),
"ymir-emu/ymir": ("Ymir", "主机模拟", "console-emulation", ["saturn"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Sega Saturn 模拟器（C++），Windows/Linux/macOS。",
  "库里 Saturn 分区此前是 0 条，这是该平台的第一个条目。1108★，11 月仍在推送。"),
"grumpycoders/pcsx-redux": ("PCSX-Redux", "主机模拟", "console-emulation", ["ps1"], ["windows", "linux", "macos"], "emulator", "desktop",
  "PS1 模拟器 + 逆向调试工具链，含 MIPS 重编译器、硬件测试与内存断点。",
  "库里 PS1 只有 ARMSX1 与 DukeStation 两个小众条目，缺主流开源实现；1038★。"),
"has207/xenia-edge": ("Xenia Edge", "主机模拟", "console-emulation", ["xbox360"], ["windows"], "emulator", "desktop",
  "Xenia 的 Edge 分支构建，面向新游戏的兼容性试验。",
  "与 canary 并列的第二条分支构建线，586★；同样归入 xenia 家族，不单独占家族位。"),
"dborth/snes9xgx": ("Snes9x GX", "主机模拟", "console-emulation", ["snes"], ["gamecube", "wii"], "emulator", "console-homebrew",
  "Snes9x 的 GameCube / Wii 移植，dborth 维护。",
  "主机端 homebrew，不是 PC 软件；库里此前没有「跑在 Wii 上」的条目，hosts 首次引入 gamecube/wii。"),
"dirkwhoffmann/virtualc64": ("VirtualC64", "主机模拟", "console-emulation", ["c64"], ["macos"], "emulator", "desktop",
  "周期精确的 Commodore 64 模拟器，macOS 原生（Swift/Objective-C）。",
  "库里 C64 分区此前 0 条。422★，11-02 刚发 v6.2。"),
"dirkwhoffmann/vamiga": ("vAmiga", "主机模拟", "console-emulation", ["amiga"], ["macos"], "emulator", "desktop",
  "Amiga 500/1000/2000 模拟器，macOS 原生，与 VirtualC64 同一作者。",
  "库里 Amiga 分区此前 0 条。417★。"),
"dborth/vbagx": ("Visual Boy Advance GX", "主机模拟", "console-emulation", ["gba"], ["gamecube", "wii"], "emulator", "console-homebrew",
  "Visual Boy Advance 的 GameCube / Wii 移植。",
  "与 Snes9x GX 同作者同形态，350★；主机端 GBA 运行方案。"),
"dborth/fceugx": ("FCE Ultra GX", "主机模拟", "console-emulation", ["nes"], ["gamecube", "wii"], "emulator", "console-homebrew",
  "FCE Ultra 的 GameCube / Wii 移植（NES）。",
  "同系列第三个，336★。"),
"m5devs/openrom": ("OpenROM", "前端与工具", "frontend-tool", ["multi"], ["windows", "linux", "macos"], "tool", "desktop",
  "复古 ROM 工具箱：格式转换、打补丁、压缩、校验、批量整理。",
  "不是模拟器，是配套工具；294★，与库里 Termux/前端类条目同层。"),
"drhelius/geargrafx": ("Geargrafx", "主机模拟", "console-emulation", ["pc-engine"], ["windows", "linux", "macos"], "emulator", "desktop",
  "PC Engine / TurboGrafx-16 / SuperGrafx / CD-ROM² 模拟器与调试器。",
  "PC Engine 分区此前只有零星条目，这是主流的独立实现；256★。"),
"jay-day/rmg-k": ("RMG-K", "前端与工具", "frontend-tool", ["n64"], ["windows", "linux"], "frontend", "desktop",
  "基于 Mupen64 的 N64 前端，带 Kaillera 与 GekkoNet 回滚联机。",
  "N64 联机是库里空白；183★。作为前端收录，不重复计入核心模拟器。"),
"copperlinehq/copperline": ("Copperline", "主机模拟", "console-emulation", ["amiga"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Rust 写的 Amiga 模拟器。",
  "Amiga 的第二个条目，语言栈不同（Rust）；140★，11 月仍在推送。"),
"shanytc/snes9x": ("SuperSnes9x", "主机模拟", "console-emulation", ["snes"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Snes9x 的可移植分支（SuperSnes9x），保留旧前端与滤镜。",
  "与 Snes9x GX 不是一回事（一个 PC 分支、一个 Wii 移植）；124★。"),
"shadps4-emu/shadnet": ("shadNet", "前端与工具", "frontend-tool", ["ps4"], ["windows", "linux"], "tool", "desktop",
  "shadPS4 的自建联机服务端，用 C++ 重写 RPCSN。",
  "PS4 联机服务，属配套工具；78★，11 月高频构建。"),
"crazyriddler/xbased": ("xbased", "主机模拟", "console-emulation", ["xbox"], ["windows", "linux"], "emulator", "desktop",
  "初代 Xbox 模拟器，Windows/Linux。",
  "初代 Xbox 分区条目稀少；73★，11-04 仍在推。"),
"ivanizag/izapple2": ("izapple2", "主机模拟", "console-emulation", ["apple2"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Apple ][+ / //e 模拟器，Go 编写，支持多种前端。",
  "Apple II 分区此前为空；71★。新增平台值 apple2。"),
"pcsx2/mymcpp": ("myMCpp", "前端与工具", "frontend-tool", ["ps2"], ["windows", "linux", "macos"], "tool", "desktop",
  "PS2 记忆卡管理与存档编辑器（myMC 的 C++ 重写）。",
  "PS2 存档工具，不与模拟器条目混计；62★。"),
"eighttails/pc6001vx": ("PC6001VX", "主机模拟", "console-emulation", ["pc-6001"], ["windows", "linux", "macos"], "emulator", "desktop",
  "NEC PC-6001 模拟器的跨平台版本（Qt 前端）。",
  "8 位机细分平台，此前为空；54★。新增平台值 pc-6001。"),
"keklick1337/rps5e": ("RustPS5Emu", "主机模拟", "console-emulation", ["ps5"], ["windows", "linux"], "emulator", "desktop",
  "Rust 写的 PS5 模拟器，面向 Linux/Windows，早期实验阶段。",
  "PS5 的第二个独立实现；45★，版本仍是 v0.1.x-alpha，note 标实验。"),
"tixul/ngpcraft_emulator": ("NgpCraft", "主机模拟", "console-emulation", ["neo-geo-pocket"], ["windows"], "emulator", "desktop",
  "Neo Geo Pocket / Color 模拟器，带完整调试器与多语言界面。",
  "NGP 分区此前为空；33★，但功能完整度高于星标。新增平台值 neo-geo-pocket。"),
"nixxou/zaxnes": ("Zaxnes", "主机模拟", "console-emulation", ["nes"], ["windows", "linux"], "emulator", "desktop",
  "NES 模拟器，Windows/Linux。",
  "30★，小项目；收录理由是 EmuCR 上持续发版，且 NES 端缺少独立实现。"),
"dmanlfc/sm2-emu": ("SM2-EMU", "主机模拟", "console-emulation", ["arcade"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Sega Model 2 街机模拟器。",
  "街机 Model 2 在库里只有 Model 3 相关条目；27★。"),
"jberclaz/turbostage": ("TurboStage", "前端与工具", "frontend-tool", ["dos"], ["windows", "linux", "macos"], "frontend", "desktop",
  "DOSBox Staging 的图形前端，管理 DOS 游戏库并一键启动。",
  "DOS 前端，配套 DOSBox Staging；21★。"),
"habib256/pom2": ("POM2", "主机模拟", "console-emulation", ["apple2"], ["windows", "linux", "macos"], "emulator", "desktop",
  "Apple ][ / ][+ / //e / //c / //c+ 模拟器。",
  "Apple II 的第二个条目；18★。"),
"eryski/cosmou": ("CosmoU", "前端与工具", "frontend-tool", ["wiiu"], ["windows"], "frontend", "desktop",
  "Wii U 游戏在 PC 上运行的前端/兼容层。",
  "Wii U 分区此前为空；15★，形态更接近前端而非模拟器。"),
"tovarichtch/odin": ("Odin", "主机模拟", "console-emulation", ["arcade"], ["windows"], "emulator", "desktop",
  "Sega Hikaru 街机模拟器（LLE），Valkyrie 的硬分叉。",
  "Hikaru 基板此前无人覆盖；11★，属长尾平台。"),
"frantriems/vkrt-nes-remaster": ("VKRT NES Remaster", "主机模拟", "console-emulation", ["nes"], ["windows"], "emulator", "desktop",
  "NES 模拟器源码（C++20），与前端解耦。",
  "3★，实验性质；收录原因是它代表了「模拟器内核与前端分离」的一种做法。"),
}


def main():
    d = json.load(open(REG, encoding="utf-8"))
    es = d["emulators"]
    have = {e["id"] for e in es}
    have_repo = {(e.get("repo") or "").lower() for e in es}
    added, skipped = [], []

    for repo, m in META.items():
        name, cat, sec, pf, hosts, kind, rt, desc, note = m
        rid = re.sub(r"[^a-z0-9]+", "-", repo.lower()).strip("-")
        if rid in have or repo.lower() in have_repo:
            skipped.append(rid)
            continue
        try:
            info = get(f"https://ungh.cc/repos/{repo}")["repo"]
            rels = get(f"https://ungh.cc/repos/{repo}/releases").get("releases", [])
        except Exception as ex:
            print(f"  跳过 {repo}: {ex}")
            continue
        rels = sorted(rels, key=lambda r: r.get("publishedAt") or "", reverse=True)
        latest = rels[0] if rels else {}
        tag = latest.get("tag") or ""
        channel = "nightly" if re.search(r"git|nightly|build|\d{8}", tag, re.I) else "stable"
        fam = {"Vita3K/Vita3K": ("vita3k", "Vita3K 系"),
               "xenia-canary/xenia-canary": ("xenia-project-xenia", "Xenia"),
               "has207/xenia-edge": ("xenia-project-xenia", "Xenia")}.get(repo, (rid, name))
        e = {
            "id": rid, "name": name, "category": cat, "description": desc,
            "owner": repo.split("/")[0], "repo": repo, "homepage": "",
            "repo_url": f"https://github.com/{repo}", "official": True, "channel": channel,
            "latest_tag": tag,
            "latest_date": iso((latest.get("publishedAt") or "")[:10]),
            "latest_body": short(latest.get("markdown")),
            "latest_apk_name": "", "latest_apk_url": "", "latest_apk_size": None,
            "pushed_at": iso((info.get("pushedAt") or "")[:10]),
            "stars": info.get("stars"), "forks": info.get("forks"), "watchers": info.get("watchers"),
            "downloads": None, "logo_url": "",
            "releases": [{"tag": r.get("tag"), "name": r.get("name") or r.get("tag"),
                          "date": (r.get("publishedAt") or "")[:10]} for r in rels[:3]],
            "source": "github", "note": note,
            "kind": kind, "runtime": rt, "platforms": pf, "status": "active",
            "method": ("emulation" if kind == "emulator" or repo in {"jay-day/rmg-k"} else None),
            "hosts": hosts, "builds": [], "needs_review": False,
            "section": sec, "family": fam[0], "family_label": fam[1],
            "downloads_basis": "none",
            "_section_evidence": f"kind={kind}",
            "_family_evidence": "上游同项目" if fam[0] != rid else "无上游证据，自成一族",
        }
        es.append(e)
        added.append((rid, info.get("stars")))
        time.sleep(0.2)

    d["updated_at"] = datetime.now().strftime("%Y-%m-%d")
    json.dump(d, open(REG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"新增 {len(added)} 条，跳过 {len(skipped)} 条，总计 {len(es)}")
    for rid, s in added:
        print(f"  + {rid:38s} ★{s}")


if __name__ == "__main__":
    main()
