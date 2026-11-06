#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补录 EmuCR 发现器 2026-11-06 批次（18 个项目）。

来源：local-monitor/discover_emucr.py --days 7 → 23 个库外候选。
用户判定「我们只是搬运，不用在意作者风险」，故：
- 收：8 个高价值 + 4 个低优先 + 3 个 Model 3 衍生 + 3 个 PS5/Switch2 实验项目
- 不收：x360ce（手柄映射工具）、emuze（启动前端）、KytyPlus（Kyty 重打包）、
        Kega-Fusion ×2（同一项目两个重打包仓库）—— 理由与作者无关，是「不是模拟器」或「重复」

原则：
- 只补元数据与发行信息，不伪造下载量（downloads=null, downloads_basis=none）
- releases 只存 tag/name/date（历史 body 不进库，最新说明放 latest_body）
- note 写明「为什么收 / 和库里已有条目什么关系」，避免以后重复收
- 不改动任何已有条目；已存在同 id 直接跳过

用法：
    python3 scripts/add_emucr_batch_20261106.py --dry-run   # 只抓不写
    python3 scripts/add_emucr_batch_20261106.py             # 写库
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from infer import builds_from_assets  # noqa: E402

REG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "emulator-registry.json")
TOKEN = ""
try:
    TOKEN = open("/root/.codebuddy/.secrets/gh-token", encoding="utf-8").read().strip()
except Exception:
    pass

UA = "emubox-batch/1.0"


def api(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/vnd.github+json",
        **({"Authorization": f"Bearer {TOKEN}"} if TOKEN else {}),
    })
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429) and attempt < 2:
                time.sleep(20 * (attempt + 1))
                continue
            if e.code == 404:
                return None
            raise
        except Exception:
            if attempt < 2:
                time.sleep(3)
                continue
            raise
    return None


# ── 本批名单 ────────────────────────────────────────────────────────
# 字段说明：platforms / hosts 为人工判定（冷门平台 infer 会漏），其余自动抓取
REPOS = [
    dict(owner="TASVideos", repo="BizHawk", name="BizHawk",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["multi"], hosts=["windows", "linux", "macos"],
         desc="多机种模拟器 + TAS 工具链（C#），覆盖 NES/SNES/GB/GBA/N64/PS1/Saturn/Genesis 等，"
              "内建逐帧调试、存档回放与内存监视。",
         note="库内此前完全没有 BizHawk，是 multi 分区最大的缺口；它同时是 TAS 视频制作的事实标准，"
              "价值不止于「多跑几个平台」。EmuCR 11-06 公告带出仓库地址。"),
    dict(owner="drhelius", repo="Gearboy", name="Gearboy",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["gb", "gbc"], hosts=["windows", "linux", "macos", "android"],
         desc="Game Boy / Game Boy Color / Super Game Boy 模拟器与调试器，C++ 跨平台，带内存查看与断点。",
         note="drhelius 的 Gear* 系列是本次一批补齐的（Gearboy/Gearsystem/Gearcoleco/Gearlynx），"
              "此前库里四个都没有，属于同作者整族遗漏。"),
    dict(owner="Rosalie241", repo="RMG", name="RMG",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["n64"], hosts=["windows", "linux", "macos"],
         desc="Mupen64Plus 的 Qt 图形前端（Rosalie's Mupen GUI），插件与存档状态管理完善，N64 桌面首选外壳。",
         note="库里此前只有 jay-day/RMG-K 分支（183★），本体 1142★ 缺失——收了分支漏了上游。归入 rmg 家族。",
         family="rosalie241-rmg"),
    dict(owner="drhelius", repo="Gearsystem", name="Gearsystem",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["master-system", "game-gear", "sg-1000"],
         hosts=["windows", "linux", "macos", "android"],
         desc="Sega Master System / Game Gear / SG-1000 模拟器与调试器，含 SC-3000 与 Mark III 兼容。",
         note="与 Gearboy 同作者。库里 master-system/game-gear 各只有 1 条，sg-1000 此前为 0。"),
    dict(owner="NextendoNetwork", repo="Ryujinx-Nextendo", name="Ryujinx Nextendo",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["switch"], hosts=["windows", "linux", "macos"],
         desc="Ryujinx 分支，内置 Nextendo 账号与联机服务，主打本地/在线多人。",
         note="用户确认收（我们只做索引与搬运，不对项目合法性背书）。与库内 ryujinx 系并列，归入 Ryujinx 系。"
              "联机依赖其私有账号服务，可用性未实测。",
         family_label="Ryujinx 系"),
    dict(owner="Arignir", repo="Hades", name="Hades",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["gba"], hosts=["windows", "linux", "macos"],
         desc="GBA 模拟器（Rust），主打指令级精确与低延迟音频，2026 年新项目、月度活跃。",
         note="库内 GBA 条目偏安卓端，缺一个现代桌面原生实现；258★ 但增势快，本月仍在提交。"),
    dict(owner="mattias800", repo="prosper", name="Prosper",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["ps5"], hosts=["windows", "linux"],
         desc="用户态 PS5 → PC 兼容层，Windows/Linux，靠静态重编译 + HLE 跑商业游戏，实验阶段。",
         note="用户确认收。库内 PS5 已有 kytyps5 / sharpemu / rps5e 三条，这是第四条且走不同技术路线"
              "（重编译而非翻译），保留作对照。"),
    dict(owner="ColinPitrat", repo="caprice32", name="Caprice32",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["amstrad-cpc"], hosts=["windows", "linux", "macos"],
         desc="Amstrad CPC 系列（464/664/6128）模拟器，SDL 前端，含磁盘镜像与快照功能。",
         note="Amstrad CPC 平台此前 0 条，这是该平台第一个条目。187★，长期维护。"),
    dict(owner="drhelius", repo="Gearcoleco", name="Gearcoleco",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["colecovision"], hosts=["windows", "linux", "macos", "android"],
         desc="ColecoVision 与 ADAM 电脑模拟器，带调试器，C++ 跨平台。",
         note="ColecoVision 平台此前 0 条。drhelius 系列第四个项目。"),
    dict(owner="drhelius", repo="Gearlynx", name="Gearlynx",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["atari-lynx"], hosts=["windows", "linux", "macos", "android"],
         desc="Atari Lynx 模拟器与调试器，C++ 跨平台，支持 ComLynx 联机。",
         note="Atari Lynx 此前只有 libretro 的 Handy 核心（16★），缺一个独立可执行实现。"),
    dict(owner="iStark", repo="PS5PCEM", name="PS5 PC Emulator",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["ps5"], hosts=["windows"],
         desc="PS5 模拟器实验项目，Windows 端，早期阶段。",
         note="用户确认收。80★，功能完整度低，note 标注「实验性」以免用户误以为可用；"
              "收它的理由是 PS5 是库内最薄的平台（10 条），需要尽量铺开候选面。"),
    dict(owner="blueminder", repo="supermodel-dojo", name="Supermodel Dojo",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["model3"], hosts=["windows", "linux", "macos"],
         desc="Supermodel 分支，主打 Sega Model 3 街机的网络对战与训练模式。",
         note="Model 3 平台库内只有 trzy/supermodel3 一条。本批一次收三个衍生（Dojo 联机 / Vulkan 重写 / Sinden 光枪），"
              "分别解决原版没有的联机、渲染后端、光枪支持，不算重复。归入 supermodel3 家族。",
         family="supermodel3", family_label="Model 3 系"),
    dict(owner="Igloo-Garage", repo="Model-3-Vulkan", name="Model-3-Vulkan",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["model3"], hosts=["windows", "linux"],
         desc="Supermodel 的 Vulkan 渲染后端独立重写版（原 Supermodel-VK），画面与性能取向。",
         note="与 supermodel-dojo 同一上游、不同侧重：这条换的是渲染后端，原版只有 OpenGL。归入 supermodel3 家族。",
         family="supermodel3", family_label="Model 3 系"),
    dict(owner="DirtBagXon", repo="model3emu-code-sinden", name="Model 3 Sinden",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["model3"], hosts=["windows", "linux", "macos"],
         desc="model3emu 分支，加入 Sinden 光枪与双人光枪支持，面向实体街机柜改造。",
         note="第三条 Model 3 衍生，价值在光枪外设支持（原版无）。归入 supermodel3 家族。",
         family="supermodel3", family_label="Model 3 系"),
    dict(owner="mentalfoundry", repo="Pokket", name="Pokket",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="libretro", method="emulation",
         platforms=["pocketstation"], hosts=["windows", "linux", "macos", "android"],
         desc="Sony PocketStation 模拟器核心，以 libretro core 形式分发。",
         note="PocketStation 平台此前 0 条，这是第一个条目；以 libretro 核心形态存在，runtime 标 libretro。"),
    dict(owner="dborth", repo="dosbox-wii", name="DOSBox Wii",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="console-homebrew", method="emulation",
         platforms=["dos"], hosts=["gamecube", "wii"],
         desc="DOSBox 的 GameCube / Wii 移植，在主机上跑 DOS 游戏。",
         note="与库内 snes9xgx / vbagx 同作者同形态（主机端 homebrew），hosts 用 gamecube/wii 而非 PC。"),
    dict(owner="p2000t", repo="M2000", name="M2000",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["philips-p2000"], hosts=["windows", "linux"],
         desc="Philips P2000T / P2000M 家用电脑模拟器（Pascal 系机型）。",
         note="Philips P2000 平台此前 0 条。15★ 但为该平台唯一开源实现，收录价值在覆盖而非热度。"),
    dict(owner="zortos293", repo="Lichii", name="Lichii",
         section="console-emulation", category="主机模拟", kind="emulator",
         runtime="desktop", method="emulation",
         platforms=["switch2"], hosts=["windows", "linux"],
         desc="Nintendo Switch 2 模拟器（早期实验阶段），与 Eglantine 同作者。",
         note="用户确认收（只做搬运不做背书）。与库内 djihads80/eglantine 同作者、同为 Switch 2 实验项目，"
              "星标增长异常快，note 标注「实验性、可用性未验证」。"),
]


def iso(s):
    return (s or "")[:10] + "T00:00:00Z" if s else ""


def main():
    dry = "--dry-run" in sys.argv
    reg = json.load(open(REG, encoding="utf-8"))
    ems = reg["emulators"]
    have = {e["id"] for e in ems}
    have_repo = {(e.get("owner", "").lower(), (e.get("repo", "").split("/")[-1]).lower()) for e in ems}

    added, skipped = [], []
    for m in REPOS:
        eid = f'{m["owner"]}-{m["repo"]}'.lower().replace("_", "-")
        if eid in have or (m["owner"].lower(), m["repo"].lower()) in have_repo:
            skipped.append(eid)
            print(f"跳过（已存在） {eid}")
            continue
        full = f'{m["owner"]}/{m["repo"]}'
        meta = api(f"https://api.github.com/repos/{full}")
        if not meta:
            print(f"!! 抓取失败 {full}")
            skipped.append(eid)
            continue
        rels = api(f"https://api.github.com/repos/{full}/releases?per_page=10") or []
        rels = [r for r in rels if not r.get("draft")]
        latest = rels[0] if rels else {}
        assets = latest.get("assets") or []
        apk = sorted([a for a in assets if (a.get("name") or "").lower().endswith(".apk")],
                     key=lambda a: -(a.get("size") or 0))

        entry = {
            "id": eid,
            "name": m["name"],
            "category": m["category"],
            "description": m["desc"],
            "owner": m["owner"],
            "repo": full,
            "homepage": meta.get("homepage") or "",
            "repo_url": meta.get("html_url") or f"https://github.com/{full}",
            "official": True,
            "channel": "prerelease" if latest.get("prerelease") else "stable",
            "latest_tag": latest.get("tag_name") or "",
            "latest_date": ((latest.get("published_at") or "")[:19] + "Z") if latest.get("published_at") else "",
            "latest_body": re.sub(r"\s+", " ", latest.get("body") or "").strip()[:1200],
            "latest_apk_name": apk[0]["name"] if apk else "",
            "latest_apk_url": apk[0]["browser_download_url"] if apk else "",
            "latest_apk_size": apk[0].get("size") if apk else None,
            "pushed_at": meta.get("pushed_at") or "",
            "stars": meta.get("stargazers_count") or 0,
            "forks": meta.get("forks_count") or 0,
            "watchers": meta.get("subscribers_count"),
            "downloads": None,
            "logo_url": f'https://github.com/{m["owner"]}.png',
            "source": "github",
            "note": m["note"],
            "kind": m["kind"],
            "runtime": m["runtime"],
            "platforms": m["platforms"],
            "status": "active",
            "method": m["method"],
            "hosts": m["hosts"],
            "needs_review": False,
            "section": m["section"],
            "family": m.get("family", eid),
            "family_label": m.get("family_label") or m["name"],
            "downloads_basis": "none",
            "_section_evidence": f'kind={m["kind"]}',
            "_family_evidence": "无上游证据，自成一族" if not m.get("family") else "同上游衍生，归入同一家族",
            "releases": [
                {"tag": r.get("tag_name") or "", "name": (r.get("name") or "")[:80],
                 "date": (r.get("published_at") or "")[:10]}
                for r in rels
            ],
        }
        # 有发行资产才写 builds（用 infer 的资产→宿主识别，含压缩包文件名线索）
        if assets:
            ev = {"release_assets": [{
                "name": a.get("name"), "size": a.get("size") or 0,
                "browser_download_url": a.get("browser_download_url"),
                "download_count": a.get("download_count") or 0,
            } for a in assets]}
            builds = builds_from_assets(ev, tag=entry["latest_tag"])
            if builds:
                entry["builds"] = builds
                # hosts 以实际发行包为准，避免给人「有安卓包」的错觉（点下载拿到 exe）
                order = ["android", "windows", "macos", "linux", "ios", "harmonyos"]
                entry["hosts"] = [h for h in order if any(b["host"] == h for b in builds)]

        added.append(entry)
        print(f'  + {eid:34s} {entry["stars"]:>6}★  tag={entry["latest_tag"][:16]:16s} '
              f'asset={len(assets):2d} builds={len(entry.get("builds") or [])} '
              f'push={entry["pushed_at"][:10]}')
        time.sleep(0.6)

    print(f"\n新增 {len(added)} 条，跳过 {len(skipped)} 条")
    if dry:
        json.dump(added, open("/tmp/emucr_batch_dry.json", "w"), ensure_ascii=False, indent=1)
        print("dry-run：写入 /tmp/emucr_batch_dry.json，未改动 registry")
        return

    ems.extend(added)
    reg["emulators"] = ems
    reg["updated_at"] = datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S+08:00")
    tmp = REG + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, REG)
    print(f"写入完成：{len(ems)} 条")


if __name__ == "__main__":
    main()
