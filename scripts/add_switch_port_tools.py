#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-11-05 补录：B 站 UP 与 EmuCR 同时出现、但库内缺失的 4 个项目。

数据来源均为可核验的一手材料：
- 仓库页 sidebar JSON（description / stargazerCount / forksCount / releaseCount）
- jsDelivr 镜像的 README 正文
- releases.atom（版本序列）+ releases/expanded_assets（资产文件名）

GitHub REST API 当时被共享出口配额耗尽（remaining=0），所以没有走
extract_entry.py。缺的 downloads / logo_url / 资产 size 由
downloads-refresh.yml 与后续 extract_entry 补齐，不影响判定字段。
"""
import json
import os
import sys

REG = "emulator-registry.json"

GH = "https://github.com"


def repo_url(repo):
    return f"{GH}/{repo}"


def build(host, fmt, name, tag, repo, size=None):
    return {
        "host": host,
        "format": fmt,
        "name": name,
        "size": size,
        "url": f"{GH}/{repo}/releases/download/{tag}/{name}",
        "downloads": None,
        "tag": tag,
    }


EGLANTINE = {
    "id": "djihads80-eglantine",
    "name": "Eglantine",
    "category": "主机模拟",
    "description": (
        "开源的 Switch 2 模拟器，从 Eden / Yuzu / Sudachi 派生，作者 Camille LaVey。"
        "C++ 编写、以便携为目标，官方构建覆盖 Windows / Linux（x86_64 与 aarch64）/ macOS。"
        "稳定版仍停在 v0.1.0-rc1，日常更新走 build-NNN 夜间构建（EmuCR 上每两三天一条）。"
    ),
    "owner": "Djihads80",
    "repo": "Djihads80/Eglantine",
    "homepage": "",
    "repo_url": repo_url("Djihads80/Eglantine"),
    "official": True,
    "channel": "rc",
    "latest_tag": "v0.1.0-rc1",
    "latest_date": "2026-11-04T00:00:00Z",
    "latest_body": (
        "首个 rc 版本，提供 Windows x86_64、Linux x86_64 / aarch64（含 PGO 变体）、"
        "macOS arm64 构建。夜间构建（build-219 等）只发源码，二进制需自行编译。"
    ),
    "latest_apk_name": "",
    "latest_apk_url": "",
    "latest_apk_size": None,
    "pushed_at": "2026-11-04T00:00:00Z",
    "stars": 3844,
    "forks": 438,
    "watchers": 49,
    "downloads": None,
    "logo_url": "",
    "releases": [
        {"tag": "v0.1.0-rc1", "name": "Eglantine v0.1.0-rc1", "date": "2026-11-04"},
        {"tag": "build-219", "name": "build-219", "date": "2026-11-04"},
        {"tag": "build-212", "name": "Eglantine nx2 build 212", "date": "2026-11-04"},
        {"tag": "build-206", "name": "Eglantine nx2 build 206", "date": "2026-11-04"},
    ],
    "source": "github",
    "note": (
        "证据：仓库描述「VERY experimental Nintendo Switch 2 emulator」；README 写明 derived from "
        "Eden, Yuzu and Sudachi、作者 Camille LaVey、开发主库在 git.eden-emu.dev，GitHub 是发布镜像。"
        "README 声称有 Android 构建，但 v0.1.0-rc1 的 12 个资产里没有任何 android 包，"
        "所以 hosts 只记 win/linux/macos——安卓端目前无官方分发，别让用户以为能直接装。"
        "未归入 Eden 家族：它是独立产品线（Switch 2 方向），合并会让榜上看不到这条。"
    ),
    "kind": "emulator",
    "runtime": "desktop",
    "platforms": ["switch2"],
    "status": "active",
    "method": "emulation",
    "hosts": ["windows", "linux", "macos"],
    "builds": [
        build("windows", "zip", "eglantine-windows-x86_64.zip", "v0.1.0-rc1", "Djihads80/Eglantine"),
        build("linux", "appimage", "eglantine-linux-x86_64.AppImage", "v0.1.0-rc1", "Djihads80/Eglantine"),
        build("linux", "appimage", "eglantine-linux-aarch64.AppImage", "v0.1.0-rc1", "Djihads80/Eglantine"),
        build("macos", "dmg", "eglantine-macos-arm64.dmg", "v0.1.0-rc1", "Djihads80/Eglantine"),
    ],
}

NX2ANDROID = {
    "id": "shirai91-nx2android",
    "name": "nx2android",
    "category": "原生移植",
    "description": (
        "把 Switch 游戏转成安卓原生 APK 的移植工具，不是模拟器：游戏自身的 arm64 代码在设备上原生运行，"
        "宿主替代 Switch 系统服务，NVN 图形重放到 Vulkan。选游戏目录、点 Port，产出 APK 与游戏数据。"
        "是 nxnative 的 Android 端口，需自备游戏文件与 prod.keys。"
    ),
    "owner": "shirai91",
    "repo": "shirai91/nx2android",
    "homepage": "",
    "repo_url": repo_url("shirai91/nx2android"),
    "official": True,
    "channel": "stable",
    "latest_tag": "v0.2.0",
    "latest_date": "2026-10-31T00:00:00Z",
    "latest_body": (
        "README 的游戏兼容表已按 v0.2.0 更新：Castlevania: Belmont's Curse、Metroid Dread、"
        "Paper Mario 两作、Pokémon Legends: Arceus 等进入可玩名单，多数 60fps。"
    ),
    "latest_apk_name": "",
    "latest_apk_url": "",
    "latest_apk_size": None,
    "pushed_at": "2026-10-31T00:00:00Z",
    "stars": 784,
    "forks": 108,
    "watchers": 10,
    "downloads": None,
    "logo_url": "",
    "releases": [
        {"tag": "v0.2.0", "name": "nx2android v0.2.0", "date": "2026-10-31"},
        {"tag": "v0.1.3", "name": "nx2android v0.1.3", "date": "2026-10-29"},
        {"tag": "v0.1.2", "name": "nx2android v0.1.2", "date": "2026-10-28"},
        {"tag": "v0.1.1", "name": "nx2android v0.1.1", "date": "2026-10-27"},
    ],
    "source": "github",
    "note": (
        "证据：仓库描述「A compatibility layer for porting Switch games to Android, one game at a time」；"
        "README 写明游戏 arm64 代码原生运行 + NVN 重放到 Vulkan，是 nxnative 的 Android 端口，"
        "且「Bring your own game files and keys」。v0.2.0 资产实测只有 "
        "nx2android-v0.2.0-windows-x64.zip 与 -macos-arm64.zip——工具本身跑桌面，产物 APK 装安卓，"
        "所以 hosts 同时记 android / windows / macos，别把「能在安卓玩」误导成「能装这个 zip」。"
        "GPU 限制写进描述：必须支持 BC 压缩纹理（Adreno / Xclipse），Mali 与 PowerVR 不行。"
    ),
    "kind": "tool",
    "runtime": "desktop",
    "platforms": ["switch"],
    "status": "active",
    "method": "recomp",
    "hosts": ["android", "windows", "macos"],
    "requires_original": True,
    "builds": [
        build("windows", "zip", "nx2android-v0.2.0-windows-x64.zip", "v0.2.0", "shirai91/nx2android"),
        build("macos", "zip", "nx2android-v0.2.0-macos-arm64.zip", "v0.2.0", "shirai91/nx2android"),
    ],
}

NX2APPLE = {
    "id": "lnpandy-nx2apple",
    "name": "NX2Apple",
    "category": "原生移植",
    "description": (
        "在 Apple silicon Mac 与 iPhone / iPad 上原生运行 Switch 游戏，不是模拟器："
        "游戏代码原生跑、Metal 渲染。Mac 端图形启动器一键完成解包、重写、着色器翻译、打包，"
        "产出的游戏进库后按 Play 启动。nxnative 的社区 fork，需自备游戏 dump 与 prod.keys。"
    ),
    "owner": "LNPANDY",
    "repo": "LNPANDY/NX2Apple",
    "homepage": "",
    "repo_url": repo_url("LNPANDY/NX2Apple"),
    "official": False,
    "channel": "rc",
    "latest_tag": "v1.1.1-rc1",
    "latest_date": "2026-11-04T00:00:00Z",
    "latest_body": (
        "1.1.0 起支持 Switch 2 游戏（选 .nup 或游戏目录，不需要 prod.keys）、"
        "iPhone / iPad 可视化按键布局编辑器、单壳应用 + 未签名 IPA 导出（AltStore / SideStore）、"
        "主机模式与大屏 Cover Flow 游戏库。"
    ),
    "latest_apk_name": "",
    "latest_apk_url": "",
    "latest_apk_size": None,
    "pushed_at": "2026-11-04T00:00:00Z",
    "stars": 3537,
    "forks": 482,
    "watchers": 11,
    "downloads": None,
    "logo_url": "",
    "releases": [
        {"tag": "v1.1.1-rc1", "name": "NX2Apple 1.1.1 release candidate 1", "date": "2026-11-04"},
        {"tag": "v1.1.0", "name": "v1.1.0: Switch 2 games, touch controls", "date": "2026-11-04"},
        {"tag": "v1.1.0-rc2", "name": "NX2Apple 1.1.0 release candidate 2", "date": "2026-11-03"},
        {"tag": "v1.1.0-rc1", "name": "NX2Apple 1.1.0 release candidate 1", "date": "2026-11-03"},
    ],
    "source": "github",
    "note": (
        "证据：README 首屏「Not an emulator. No terminal.」「Your Switch games, running on your iPhone.」；"
        "正文写明是 nxnative（seedds/nxnative）的社区 fork，fork 只加启动器与少量 patch，"
        "真正的代码重写器在 nxnative。要求 Apple silicon Mac + macOS 13，iPhone/iPad 需 iOS 26+。"
        "official 记 false：它不是 nxnative 官方，但仓库属于作者本人、非第三方重打包。"
        "提供 README.zh-CN.md / .sv / .de / .fr / .it 六语，中文用户可直接读。"
    ),
    "kind": "tool",
    "runtime": "desktop",
    "platforms": ["switch", "switch2"],
    "status": "active",
    "method": "recomp",
    "hosts": ["macos", "ios"],
    "requires_original": True,
    "builds": [
        build("macos", "zip", "NX2Apple-macOS-arm64.zip", "v1.1.0", "LNPANDY/NX2Apple"),
        build("macos", "zip", "NS-Port-Builder-macOS-arm64.zip", "v1.1.0", "LNPANDY/NX2Apple"),
    ],
}

NSP_BOX = {
    "id": "smarkyie-nsp-box",
    "name": "NSP Box",
    "category": "前端与工具",
    "description": (
        "Windows 便携工具：把已解包的 ExeFS / RomFS 快照打包成明文 NSP，4.0 起支持压缩 NUP 归档与互转。"
        "可挑选 Base / Update / DLC、加自定义封面与裁切、校验归档；内置 Emulator patch 面板，"
        "为 Selevet / Eglantine nx2 / PokeDinner 等 Windows x64 模拟器写入 NSP 元数据。"
    ),
    "owner": "smarkyie",
    "repo": "smarkyie/NSP-Box",
    "homepage": "",
    "repo_url": repo_url("smarkyie/NSP-Box"),
    "official": True,
    "channel": "stable",
    "latest_tag": "4.0.0",
    "latest_date": "2026-11-03T00:00:00Z",
    "latest_body": (
        "4.0 引入 NUP 工具：Fast / Balanced / Smaller 三档压缩、按来源选择 Base / Updates / DLC、"
        "去掉 32 GiB 的 NSP 体积上限、修正大窗口 Zstandard 帧解码。"
    ),
    "latest_apk_name": "",
    "latest_apk_url": "",
    "latest_apk_size": None,
    "pushed_at": "2026-11-03T00:00:00Z",
    "stars": 469,
    "forks": 32,
    "watchers": 8,
    "downloads": None,
    "logo_url": "",
    "releases": [
        {"tag": "4.0.0", "name": "NSP Box 4.0.0 — Meet NUP", "date": "2026-11-03"},
        {"tag": "3.1.0", "name": "NSP Box 3.1.0", "date": "2026-11-02"},
        {"tag": "3.0.0", "name": "NSP Box 3.0.0", "date": "2026-11-01"},
        {"tag": "2.0.0", "name": "Switch 2 Game Packager 2.0.0", "date": "2026-11-01"},
    ],
    "source": "github",
    "note": (
        "证据：仓库描述「A Switch 2 Game Packager ... with reversible emulator patching」；"
        "README 写明是 portable Windows app、无 Linux 构建、只处理「already-extracted ExeFS/RomFS」。"
        "它不是模拟器，也不是「NS1 模拟器魔改跑 NS2」——UP 主标题说的那层能力来自它给 "
        "Eglantine nx2 / Selevet / PokeDinner 打的 patch 面板。4.0.0 的 tag 在 GitHub 上是 404，"
        "资产名待 CI 核实，所以 builds 暂空。"
    ),
    "kind": "tool",
    "runtime": "desktop",
    "platforms": ["switch2"],
    "status": "active",
    "method": None,
    "hosts": ["windows"],
    "builds": [],
}

NEW = [EGLANTINE, NX2ANDROID, NX2APPLE, NSP_BOX]


def main():
    d = json.load(open(REG, encoding="utf-8"))
    ems = d["emulators"]
    have = {e["id"] for e in ems}
    added = []
    for e in NEW:
        if e["id"] in have:
            print(f"跳过（已存在）: {e['id']}")
            continue
        e.setdefault("needs_review", False)
        e.setdefault("section", "")
        e.setdefault("family", e["id"])
        e.setdefault("family_label", e["name"])
        ems.append(e)
        added.append(e["id"])
    if added:
        d["updated_at"] = "2026-11-05"
        json.dump(d, open(REG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"新增 {len(added)} 条: {added}")
    print(f"总数 {len(ems)}")


if __name__ == "__main__":
    sys.exit(main())
