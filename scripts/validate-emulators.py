#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器注册表自动验证：来源标记完整性、dead 一致性、下载直链合法性。
输出逐条问题 + 通过率，供 CI 接入与提交前自检。"""
import json
import re
import sys
import urllib.request

REG = "data/emulator-registry.json"
DEAD_SOURCES = {"yuzu-official", "citron-emulator",
                "strato-emulator", "ryujinx-official",
                "ovsky-sumi-emu", "skyline-official", "drastic",
                # 2026-11-16：自动复核 GitHub 返回 404/451 后标记，仓库确已下架
                "metroidprimeport", "odrannnn-metroidprime2port",
                "prosperoeden-ps5", "eden-nx2-zuan",
                "nextendonetwork-ryujinx-nextendo", "lichii-enhanced",
                "torzu-emulator",
                # 2026-11-17：citron-neo/ci 返回 451，脚本已判死但没有登记进清单
                "citron-ci",
                # 2026-11-20：每日刷新把这几条标了 dead 却没同步清单，CI 因此一直红着。
                # 逐个实测均 404，且搜不到同名新仓（不是改名）：sj0404-collab 名下两个仓消失。
                "sj0404-collab-symbiosis", "sj0404-collab-winlator-hybrid-dxvk",
                # 2026-11-26：simipoB 的两个仓库实测 200 且 11-25 还在推版本，账号已恢复
                # （11-20 判死时整个账号 404），从清单移除。同日补登 huevoman77-redu：
                # API 仍 404、搜索无同名新仓，仓库确已删除，之前标了 dead 却漏登清单。
                "huevoman77-redu",
                # 2026-11-21：每日刷新又标了两个，同样没同步清单。
                # afeimod/linbox 11-17 还在发 prefix-pack 版，今天 API 直连 404；
                # 账号还在、名下 37 个仓库里没有它 —— 删库或转私有，用户都拿不到。
                # ptyfyre 整个账号 404（与 simipoB 一样），mandarine-neo 随之失效。
                "afeimod-linbox", "mandarineneo-3ds",
                # 2026-11-27：a2redweb/nx2android 实测 404，搜索无同名新仓（20 个结果里
                # 没有它），确认删库；每日刷新已标 dead，清单同步。
                "a2redweb-nx2android-native-port"}
# 注意：这份清单是「已确认下架」的快照，不是判死规则。
# afeimod-linbox 曾在这里，但 11-16 还在发新版（GitHub 当日 404 属于误判），
# 已移出：清单只记「现在确实打不开」的，判死仍交给刷新脚本每日复核。
# moceure-banananative 曾在这里，但它的主仓在 Eden 自建 Gitea（git.eden-emu.dev），
# 拿 GitHub 的 404 去判一个 Gitea 项目的死是错的：11-15 还发了 v0.3.26。
# 加新条目之前先确认「这个 source 对应的平台」确实 404，别再跨平台判死。
VALID_SOURCES = {"github", "gitea", "gitlab", "gitee", "notabug",
                 "official-web", "official-play", "community-archive"}
BAD_EXT = {".json", ".md", ".txt", ".whp", ".whpx", ".zip", ".tar.gz", ".7z"}
# 已知的自建 Gitea / Forgejo 实例。source=gitea 的 repo_url 必须落在其一。
GITEA_HOSTS = ("git.eden-emu.dev", "git.ryujinx.app", "git.mythrax-rs.org")
# 桌面 / libretro 条目的分发物本就是 zip / tar.gz / AppImage / exe，
# 对它们套用「安卓安装包」扩展名黑名单属于误判。
DESKTOP_RUNTIMES = {"desktop", "libretro"}
DESKTOP_OK_EXT = (".zip", ".tar.gz", ".tar.xz", ".tgz", ".7z", ".exe", ".dmg",
                  ".deb", ".appimage", ".ipa", ".tar", ".xz")


def head_check(url, timeout=12):
    try:
        req = urllib.request.Request(url, method="HEAD",
                                     headers={"User-Agent": "emubox-validate"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status < 400
    except Exception:
        return None


def main():
    d = json.load(open(REG, encoding="utf-8"))
    ems = d.get("emulators", [])
    problems = []
    seen_ids = set()
    for e in ems:
        eid = e.get("id", "")
        if not eid:
            problems.append("[无id] 条目缺 id")
            continue
        if eid in seen_ids:
            problems.append(f"[重复id] {eid}")
        seen_ids.add(eid)

        src = e.get("source", "")
        if not src:
            problems.append(f"[{eid}] 缺 source 字段")
        elif src not in VALID_SOURCES:
            problems.append(f"[{eid}] source 非法: {src}")

        url = e.get("repo_url") or ""
        if url:
            if not url.startswith("http"):
                problems.append(f"[{eid}] repo_url 非 http: {url[:50]}")
            if src == "github" and "github.com" not in url:
                problems.append(f"[{eid}] source=github 但 repo_url 非 GitHub: {url[:60]}")
            if src == "gitlab" and "gitlab.com" not in url:
                problems.append(f"[{eid}] source=gitlab 但 repo_url 非 GitLab: {url[:60]}")
            if src == "gitea" and not any(h in url for h in GITEA_HOSTS):
                problems.append(f"[{eid}] source=gitea 但 repo_url 非已知 Gitea 实例: {url[:60]}")

        dead = e.get("dead", False)
        if dead and eid not in DEAD_SOURCES:
            problems.append(f"[{eid}] dead=true 但不在 DEAD 清单（需同步清单）")
        if eid in DEAD_SOURCES and not dead:
            problems.append(f"[{eid}] DEAD 清单含此 id 但未标 dead")
        if dead and not e.get("dead_reason"):
            problems.append(f"[{eid}] dead=true 缺 dead_reason")

        apk = e.get("latest_apk_url") or ""
        if apk:
            if not apk.startswith("http"):
                problems.append(f"[{eid}] latest_apk_url 非 http: {apk[:50]}")
            low = apk.lower()
            if e.get("runtime") in DESKTOP_RUNTIMES:
                # 桌面条目：只要不是明显不可执行的文件（json/md/txt）即视为合法分发物
                if low.endswith((".json", ".md", ".txt")):
                    problems.append(f"[{eid}] 桌面分发物不应是文档文件: {apk[-30:]}")
            elif (any(low.endswith(x) for x in BAD_EXT)
                  and e.get("distribution") != "android-archive"
                  and "RetroArch" not in (e.get("note") or "")):
                # 安卓包大量打成 zip 发（EX-Emulators.zip / nc2000_*.zip /
                # FivePocket-*-android.zip），distribution=android-archive 即属此列，
                # 按扩展名判死会误杀。只有 distribution 不是 android-archive 时才算可疑。
                problems.append(f"[{eid}] 下载直链为可疑文件类型(非安装包): {apk[-30:]}")
        elif not dead and e.get("source") in ("github", "gitlab", "gitea", "notabug"):
            # 活跃代码源条目应有下载或明确 note
            if not e.get("note"):
                problems.append(f"[{eid}] 活跃代码源无下载直链且无 note 说明")

    # ── 新字段校验：hosts / method / builds ──
    METHOD_OK = {"emulation", "translation", "recomp", "engine", "virtualization", None}
    HOST_OK = {"android", "windows", "linux", "macos", "ios", "harmonyos",
               # 主机自制固件宿主：PS5 homebrew 等。目前只有 1 条（ProsperoEden），
               # 但它是真实存在的宿主，塞进 android 会误导「安卓端能装」的判断。
               "ps5",
               # 主机 homebrew 宿主：dborth 的 GX 系列（Snes9x/VBA/FCE Ultra）跑在
               # GameCube/Wii 上，不是 PC 软件，也不该算 desktop。
               "gamecube", "wii",
               # 2026-11-12 补：izpsx 跑在 Switch 2（需 IZ2S 平台权限），
               # emufive 跑在已越狱 PS4 上通过 Linux 跑 PS5 游戏。
               # 二者都是 console-homebrew，hosts 写主机名才是真实语义；
               # 以前白名单没收这两个值，导致把正确数据判成非法。
               "switch2", "ps4"}
    for e in ems:
        eid = e.get("id")
        if "method" not in e:
            problems.append(f"[{eid}] 缺 method 字段")
        elif e["method"] not in METHOD_OK:
            problems.append(f"[{eid}] method 非法值: {e['method']}")
        hs = e.get("hosts")
        if hs is None:
            problems.append(f"[{eid}] 缺 hosts 字段")
        elif not isinstance(hs, list):
            problems.append(f"[{eid}] hosts 不是数组")
        else:
            bad = [h for h in hs if h not in HOST_OK]
            if bad:
                problems.append(f"[{eid}] hosts 含非法值: {bad}")
            if len(hs) != len(set(hs)):
                problems.append(f"[{eid}] hosts 有重复项")
        for b in (e.get("builds") or []):
            if not isinstance(b, dict) or "host" not in b or "url" not in b:
                problems.append(f"[{eid}] builds 项结构不完整")
                break

    print(f"条目总数: {len(ems)}")
    print(f"发现问题: {len(problems)}")
    for p in problems:
        print("  ", p)
    if problems:
        print(f"\n通过率: {len(ems) - len(problems)}/{len(ems)}（按条目计，问题逐条列出）")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
