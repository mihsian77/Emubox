#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按本轮溯源结论修正 emulator-registry.json。

三类改动：
1. COMMUNITY_FIX  —— 社区改名包：从"无一手来源"改为实际查到的仓库/官网/失效判定
2. CLOSED_FIX     —— 闭源/商店项目：修正错误链接、补可跳转的官方分发地址
3. ADDITIONS      —— 新收录有价值的续集/分支仓库（含 PC 移植，单独分类）

每条改动都带 evidence 字段，记录核验方式，便于后续复查。
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")

# ---------------------------------------------------------------------------
# 1. 社区改名包：溯源结果
# ---------------------------------------------------------------------------
# 说明：
#   CitruS —— 真实身份是 yuzu 的 fork "citron"（Forgejo git.ryujinx.app/archive/citron）。
#             该仓库完整提交历史里可查到 2013-09-13 "renamed project to 'citrus'"，
#             13 天后 "renamed from citrus to citra" —— Citra 本名就是 Citrus，
#             所以 CitruS 命名是 Citra 血统延续，不是臆造词。
#   同时 Gitee fanwenjie/citrus 是活跃的 yuzu 源码树镜像（GPL-3.0，带 wiki）。
#   Skyline Pine —— 上游 ishan09811/pine 已 DMCA 下架，
#             社区分支 xmy953538104/pine_CN 存活，2026-10-23 仍活跃，
#             versionName 2.1.1 与 App 库记录吻合，release 附 APK。
#   Skyline Enhanced Infinity —— 上游 Ishan0981/Skyline-Enhanced-Infinity
#             ★22 MPL-2.0，2026-02-17 DMCA 下架（GitHub API 现已 404/MISSING）。
#   Skyline Tagline —— GitHub/Gitee/GitLab/Wayback 全网零命中，确认无一手来源。
#   Yuzu+ —— 包名 org.yuzu.yuzu_plus，GitHub 全站 0 命中，确认无一手来源。
#   EclipseDS —— 实际是自研新项目（包名 com.eclipseds.app，v0.1），
#             不是任何既有项目的改名版，原标注"社区改名 DS 模拟器"有误。
#   Ryujinx Benji-SC —— 是 Kenji-NX 的 fork，源码在 Forgejo git.ryujinx.app
#             （projects/Kenji-NX 上游），GitHub 上的 maxjivi05/Benji-SC 已 404，
#             但 Forgejo 上 BeZide/kenji-sc 分支存在过，现亦已移除。
#             一手来源 = git.ryujinx.app/projects/Kenji-NX + Ryubing/Canary 构建仓。
COMMUNITY_FIX = {
    "eclipseds-nds": {
        "description": "NDS 安卓模拟器，自研新项目（包名 com.eclipseds.app，v0.1），非任何既有项目的改名版。仅第三方下载页收录，无源码仓库",
        "note": "溯源结论：并非「社区改名包」。GitHub 搜 com.eclipseds.app 零命中，仅 j9p.com/game/647563.html 等下载页收录，包名 com.eclipseds.app 官网标注「暂无」。属未开源独立 App，无一手仓库",
        "skip": "no_repo",
        "distribution": "community-archive",
        "status": "community",
        "community_sources": [
            {"repo": "(无公开源码仓库)", "note": "包名 com.eclipseds.app，GitHub 检索零命中；仅第三方下载页收录，包体未经安全验证"}
        ],
    },
    "ryujinx-benji-sc": {
        "description": "Ryujinx 系社区分支 Benji-SC（Kenji-NX 的 fork，主打快捷键/UI 体验）。一手源码与构建在 Forgejo git.ryujinx.app",
        "owner": "Kenji-NX",
        "repo": "projects/Kenji-NX",
        "repo_url": "https://git.ryujinx.app/projects/Kenji-NX",
        "homepage": "https://ryujinx.app",
        "source": "gitea",
        "official": False,
        "skip": "",
        "distribution": "forgejo-release",
        "status": "active",
        "note": "溯源结论：改名为「Ryujinx」是社区词（Ryujinx 本体 2024-10 DMCA 停更），但 Benji-SC 与 Ryubing/Kenji-NX 均有公开源码与官方构建仓。Forgejo projects/Kenji-NX release 2.0.5（linux AppImage/tar.gz），Ryubing/Canary 每次提交发版（最新 1.3.352），Android APK 由 Kenji-NX/Android 仓发布（2.1.0-pr.2，272090 次下载）。GitHub 上 maxjivi05/Benji-SC 镜像已 404",
        "latest_tag": "2.1.0-pr.2",
        "latest_apk_name": "kenji-nx-v2.1.0-pr.2-standard.apk",
        "latest_apk_url": "https://git.ryujinx.app/Kenji-NX/Android/releases/download/2.1.0-pr.2/kenji-nx-v2.1.0-pr.2-standard.apk",
        "latest_date": "2026-02-13T00:00:00Z",
        "community_sources": [
            {"repo": "https://git.ryujinx.app/projects/Kenji-NX", "note": "一手源码（GPL-3.0）"},
            {"repo": "https://git.ryujinx.app/Ryubing/Canary", "note": "每次提交自动发版构建仓"},
            {"repo": "https://git.ryujinx.app/Kenji-NX/Android", "note": "Android APK release 仓"},
        ],
    },
    "skyline-pine": {
        "description": "Skyline 分支 Pine，社区中文分支 pine_CN 存活并持续发版（含 APK）",
        "owner": "xmy953538104",
        "repo": "xmy953538104/pine_CN",
        "repo_url": "https://github.com/xmy953538104/pine_CN",
        "homepage": "",
        "source": "github",
        "official": False,
        "skip": "",
        "distribution": "release-apk",
        "status": "active",
        "note": "溯源结论：上游 ishan09811/pine 已于 DMCA 后下架（GitHub API MISSING），社区分支 xmy953538104/pine_CN 存活，2026-10-23 仍活跃，GPL-3.0，release 直接附 APK（app-full-debug.apk），versionName 2.1.1 与本库记录吻合",
        "latest_tag": "v2.1.2-diagnostics-v1.3",
        "latest_date": "2026-10-17T00:00:00Z",
        "latest_apk_name": "app-full-debug.apk",
        "latest_apk_url": "https://github.com/xmy953538104/pine_CN/releases/download/v2.1.2-diagnostics-v1.3/app-full-debug.apk",
        "community_sources": [
            {"repo": "https://github.com/xmy953538104/pine_CN", "note": "存活社区分支，release 含 APK"},
            {"repo": "https://github.com/ishan09811/pine", "note": "已下架的上游"},
        ],
    },
    "skyline-enhanced-infinity": {
        "description": "Skyline 分支 Enhanced Infinity（原上游已 DMCA 下架，仅历史版本可考）",
        "owner": "Ishan0981",
        "repo": "Ishan0981/Skyline-Enhanced-Infinity",
        "repo_url": "https://github.com/Ishan0981/Skyline-Enhanced-Infinity",
        "source": "github",
        "official": False,
        "skip": "",
        "distribution": "community-archive",
        "status": "archived",
        "note": "溯源结论：上游 Ishan0981/Skyline-Enhanced-Infinity ★22 MPL-2.0，2026-02-17 因 DMCA 下架（GitHub API 现已 404）。历史版本 v1.2.0 仅经网盘与视频实测流传，包体未经安全验证",
        "community_sources": [
            {"repo": "https://github.com/Ishan0981/Skyline-Enhanced-Infinity", "note": "已下架，★22 MPL-2.0"},
        ],
    },
    "skyline-tagline": {
        "description": "Skyline 停更后的社区改名版。经 GitHub/Gitee/GitLab/Wayback 全网检索，未发现任何一手来源",
        "note": "溯源结论：确认无一手来源。GitHub（含 readme/description 全文检索）、Gitee、GitLab、Wayback 存档与中文合集页全部零命中，唯一命中是本 App 自身记录。仅网盘/Telegram 群内分发，无法给出可跳转的官方链接",
        "skip": "no_repo",
        "distribution": "community-archive",
        "status": "community",
        "community_sources": [
            {"repo": "(全网无一手来源)", "note": "多平台零命中证据，无法提供可跳转分发地址"}
        ],
    },
    "yuzu-citrus": {
        "description": "yuzu 的社区续作 CitruS / citron。一手源码在 Forgejo git.ryujinx.app/archive/citron，另有 Gitee fanwenjie/citrus 源码树",
        "owner": "archive",
        "repo": "archive/citron",
        "repo_url": "https://git.ryujinx.app/archive/citron",
        "homepage": "https://gitee.com/fanwenjie/citrus",
        "source": "gitea",
        "official": False,
        "skip": "",
        "distribution": "forgejo-release",
        "status": "community",
        "note": "溯源结论：不是「无来源的改名包」。Forgejo archive/citron 是真实 yuzu 源码树（提交历史 28198 条），其历史可查 2013-09-13「renamed project to 'citrus'」、13 天后「renamed from citrus to citra」—— Citra 本名即 Citrus，CitruS 属该血脉延续。Gitee fanwenjie/citrus（GPL-3.0，带 wiki）为另一份活跃源码树，但 README 链接仍指向已失效的 yuzu 官网，暂无有效下载页",
        "community_sources": [
            {"repo": "https://git.ryujinx.app/archive/citron", "note": "一手 yuzu 源码树，GPL-3.0"},
            {"repo": "https://gitee.com/fanwenjie/citrus", "note": "Gitee 源码树 + wiki，无下载页"},
        ],
    },
    "yuzu-plus": {
        "description": "yuzu 删库后的社区改名包（包名 org.yuzu.yuzu_plus）。GitHub 全站检索零命中，确认无一手来源",
        "note": "溯源结论：确认无一手来源。GitHub 全站（含 readme/code）检索 org.yuzu.yuzu_plus 与 Yuzu+ 组合均零命中，仅网盘/Telegram 分发。基座 yuzu 源码镜像仍在，但不构成该改名包自身的来源",
        "skip": "no_repo",
        "distribution": "community-archive",
        "status": "community",
        "community_sources": [
            {"repo": "(全网无一手来源)", "note": "GitHub 零命中；仅网盘/Telegram 分发"},
        ],
    },
    # Skyline 官方本体：仓库已自述「Development of Skyline has ceased」
    "skyline-official": {
        "description": "Skyline（官方）：安卓 Switch 模拟器。仓库 README 已自述开发终止，源码已从所有 owner 分支与 tag 移除，仅作历史记录",
        "owner": "skyline-emu",
        "repo": "skyline-emu/skyline",
        "repo_url": "https://github.com/skyline-emu/skyline",
        "homepage": "https://skyline-emu.one",
        "source": "github",
        "official": True,
        "skip": "",
        "distribution": "archived-repo",
        "status": "archived",
        "note": "溯源结论：仓库未删除，但 README 明确写明因任天堂要求已从所有 owner 分支和 tag 移除源码、不再分发。仓库 70★，最后提交 2026-08-18，零 release、零 tag。官网 skyline-emu.one 当前不可达",
        "community_sources": [
            {"repo": "https://github.com/skyline-emu/skyline", "note": "历史记录仓库，源码已移除"},
        ],
    },
    # Torzu：GitHub 451，主仓在 Gitee 镜像
    "torzu-emulator": {
        "description": "Torzu：Switch 模拟器。GitHub 主仓因 DMCA 返回 451，实际维护在 Gitee 镜像",
        "owner": "WinterMute",
        "repo": "WinterMute/torzu",
        "repo_url": "https://gitee.com/mirrors/torzu",
        "homepage": "",
        "source": "gitee",
        "official": True,
        "skip": "",
        "distribution": "gitee-release",
        "status": "stale",
        "note": "溯源结论：GitHub WinterMute/torzu 返回 404/451，gitee.com/mirrors/torzu 存活（镜像仓）。另有社区打包 pkgforge-dev/Torzu-AppImage（★22，2026-10-07 活跃）提供 AppImage 形态",
        "community_sources": [
            {"repo": "https://gitee.com/mirrors/torzu", "note": "Gitee 镜像仓，实际维护地"},
            {"repo": "https://github.com/pkgforge-dev/Torzu-AppImage", "note": "社区 AppImage 打包，★22"},
        ],
    },
}

# ---------------------------------------------------------------------------
# 2. 闭源 / 商店项目：链接修正
# ---------------------------------------------------------------------------
CLOSED_FIX = {
    # Kenji-NX：并非闭源，Forgejo 有公开源码与 APK
    "kenji-nx-android-releases": {
        "description": "Kenji-NX Emulator：Ryujinx 系社区延续项目。源码与 Android APK 均在自建 Forgejo（git.ryujinx.app）公开分发",
        "owner": "Kenji-NX",
        "repo": "Kenji-NX/Android",
        "repo_url": "https://git.ryujinx.app/Kenji-NX/Android",
        "homepage": "https://ryujinx.app",
        "source": "gitea",
        "official": False,
        "repo_status": "",
        "distribution": "forgejo-release",
        "status": "active",
        "note": "溯源结论：原记「闭源/kenjinx.com 分发」有误。kenjinx.com 域名当前 DNS 不可达；一手分发实际是自建 Forgejo——源码仓 projects/Kenji-NX（GPL-3.0，release 2.0.5 含 linux AppImage/tar.gz），Android APK 由 Kenji-NX/Android 发布（2.1.0-pr.2，含 standard/optimized/sd8eg5/sd8g3-8e 四个变体，官方统计 272090 次下载）",
        "latest_tag": "2.1.0-pr.2",
        "latest_date": "2026-02-13T00:00:00Z",
        "latest_apk_name": "kenji-nx-v2.1.0-pr.2-standard.apk",
        "latest_apk_url": "https://git.ryujinx.app/Kenji-NX/Android/releases/download/2.1.0-pr.2/kenji-nx-v2.1.0-pr.2-standard.apk",
        "community_sources": [
            {"repo": "https://git.ryujinx.app/projects/Kenji-NX", "note": "一手源码（GPL-3.0）"},
        ],
    },
    # Ryujinx 官方：官网 ryujinx.io 已被社区接管
    "ryujinx-official": {
        "description": "Ryujinx 官方：C# 编写的 Switch 模拟器，2024-10 因 DMCA 停更，原 GitHub 仓库已移除。当前 ryujinx.app 由社区 Ryubing 团队续接维护",
        "owner": "Ryujinx",
        "repo": "Ryujinx/Ryujinx",
        "repo_url": "https://github.com/Ryujinx/Ryujinx",
        "homepage": "https://ryujinx.app",
        "source": "official-web",
        "official": True,
        "distribution": "official-site",
        "status": "dead",
        "note": "溯源结论：ryujinx.io 仍可访问但已非维护方渠道；社区续接方 ryujinx.app 的源码指向 src.ryujinx.app（302 → https://git.ryujinx.app/projects/Ryubing），即 Ryubing 团队自建 Forgejo。社区版本收录见「Ryubing」条目",
        "community_sources": [
            {"repo": "https://git.ryujinx.app/projects/Ryubing", "note": "社区续接源码"},
            {"repo": "https://git.ryujinx.app/Ryubing/Canary", "note": "社区每次提交发版"},
        ],
    },
    # Sumi Emulator：官网已停放
    "ovsky-sumi-emu": {
        "description": "Sumi Emulator：原 GitHub 仓库 ovsky/sumi-emu 已被任天堂 DMCA 屏蔽（2026-03-31）；官网域名已落入停放商，无有效下载",
        "homepage": "https://sumi-emu.org",
        "repo_url": "https://sumi-emu.org",
        "distribution": "official-site",
        "status": "dead",
        "note": "溯源结论：sumi-emu.org 现 302 跳至 ww17.sumi-emu.org，指向域名停放商（页面正文为「domain may be for sale」）。原宣传页 sumi.framer.media 的 DOWNLOAD NOW 按钮已无 href。GitHub ovsky/sumi-emu（★592）451 DMCA；替代镜像 snapekk/sumi-emu（★1，GPL-3.0）无 release",
        "community_sources": [
            {"repo": "https://github.com/snapekk/sumi-emu", "note": "源码镜像 ★1，无 release"},
        ],
    },
    # DraStic：Play 已下架，三个域名都非官方
    "drastic": {
        "description": "DraStic（Exophase）：闭源 NDS 模拟器。2025-02 已从 Google Play 自行下架，作者承诺开源但至今未发布。官方一手渠道已失效",
        "homepage": "",
        "repo_url": "",
        "distribution": "no-official-channel",
        "status": "dead",
        "note": "溯源结论：Play 条目 com.dsemu.drastic 已下架；drasticemulator.com / drasticds.com / drastic-ds.com 三个域名均非官方（第三方 SEO 站 / ROM 引流站 / GoDaddy 停放），不可作为官方来源。末版 r2.6.0.4a (build 109)。作者 Exophase 曾表示将开源源码，迄今未兑现。任何第三方包体均非官方，不予收录为可下载分发",
        "community_sources": [],
    },
    # MYPS2：官网已关停
    "myps2-ps2": {
        "description": "MYPS2（iefriends）：闭源 PS2 模拟器，仅 Google Play 分发。2026-08 仍在更新",
        "homepage": "",
        "repo_url": "https://play.google.com/store/apps/details?id=kr.co.iefriends.myps2",
        "download_url": "https://play.google.com/store/apps/details?id=kr.co.iefriends.myps2",
        "download_kind": "google-play",
        "distribution": "official-play",
        "status": "active",
        "note": "溯源结论：Play 条目存活，v2.6 / 2026-08-26 仍更新。原官网 iefriends.modoo.at 已关停（Naver modoo 服务 2025-06-26 下线）。无源码仓库，底层基于 pontos2024/PCSX2_ARM64",
        "community_sources": [],
    },
    # Pizza Boy GBA / My Boy! / My OldBoy!：包名修正
    "pizzaboy": {
        "repo_url": "https://play.google.com/store/apps/details?id=it.dbtecno.pizzaboygba",
        "download_url": "https://play.google.com/store/apps/details?id=it.dbtecno.pizzaboygba",
        "homepage": "https://play.google.com/store/apps/details?id=it.dbtecno.pizzaboygba",
        "note": "溯源结论：Play 包名为 it.dbtecno.pizzaboygba（原记录误写 com.fastemulator.pizza_boy）。v2.3.24 / 2026-10-28，下载量 330 万+",
    },
    "myboy": {
        "repo_url": "https://play.google.com/store/apps/details?id=com.fastemulator.gba",
        "download_url": "https://play.google.com/store/apps/details?id=com.fastemulator.gba",
        "homepage": "https://play.google.com/store/apps/details?id=com.fastemulator.gba",
        "note": "溯源结论：Play 包名为 com.fastemulator.gba（原记录 com.fastemulation.gba 拼写有误）",
    },
    "myoldboy": {
        "repo_url": "https://play.google.com/store/apps/details?id=com.fastemulator.gbc",
        "download_url": "https://play.google.com/store/apps/details?id=com.fastemulator.gbc",
        "homepage": "https://play.google.com/store/apps/details?id=com.fastemulator.gbc",
        "note": "溯源结论：Play 包名为 com.fastemulator.gbc（原记录 com.fastemulation.myoldboy 不存在）",
    },
    # M64Plus FZ：包名与开源状态修正
    "m64plusfz": {
        "description": "M64Plus FZ（Mupen64Plus AE 前端）：闭源商店版，但核心为开源 GPL-3.0",
        "repo_url": "https://play.google.com/store/apps/details?id=org.mupen64plusae.v3.fzurita",
        "download_url": "https://play.google.com/store/apps/details?id=org.mupen64plusae.v3.fzurita",
        "homepage": "https://play.google.com/store/apps/details?id=org.mupen64plusae.v3.fzurita",
        "distribution": "official-play",
        "status": "active",
        "note": "溯源结论：现行 Play 包名为 org.mupen64plusae.v3.fzurita（原记录 org.mupen64plus.android.fz 是旧包名）。核心源码公开：fzurita/mupen64plus-ae（★27，GPL-3.0），其上游 mupen64plus-ae/mupen64plus-ae（★504）持续维护至 2026-08",
        "community_sources": [
            {"repo": "https://github.com/fzurita/mupen64plus-ae", "note": "一手核心源码 ★27 GPL-3.0"},
            {"repo": "https://github.com/mupen64plus-ae/mupen64plus-ae", "note": "上游 ★504，2026-08 仍活跃"},
        ],
    },
    # ePSXe / FPse：实为商店分发
    "epsxe": {
        "repo_url": "https://play.google.com/store/apps/details?id=com.epsxe.ePSXe",
        "download_url": "https://play.google.com/store/apps/details?id=com.epsxe.ePSXe",
        "homepage": "https://epsxe.com/android/",
        "distribution": "official-play",
        "note": "溯源结论：官网 android 页仅链接 Play，不自持 APK。Play 包名 com.epsxe.ePSXe，v2.0.19。无公开源码仓库",
    },
    "fpse": {
        "repo_url": "https://play.google.com/store/apps/details?id=com.emulator.fpse",
        "download_url": "https://play.google.com/store/apps/details?id=com.emulator.fpse",
        "homepage": "https://www.fpsece.net/download.html",
        "distribution": "official-play",
        "note": "溯源结论：官网首页 500，但 /download.html 存活且明确写「Need go to Market place」。现行 Play 包名为 com.emulator.fpse（原记录 com.fpsece.fpse 不存在），另有 FPseNG 变体 com.emulator.fpse64。无公开源码仓库",
    },
    # GameHub 官方
    "producdevity-gamehub": {
        "description": "GameHub（Producdevity 官方版）：Android Windows 游戏启动器，闭源，仅经官网分发。开源 Lite 版另见 gamehub-lite 条目",
        "repo_url": "https://gamehub.xiaoji.com/zh-cn",
        "homepage": "https://gamehub.xiaoji.com/zh-cn",
        "distribution": "official-site",
        "status": "active",
        "note": "溯源结论：官网下载接口 https://gamehub.xiaoji.com/zh-cn/download/apk/config 返回真实 APK 直链（wwwdl.mx520.com 托管的 GameHub_6.3.2.apk，74.7MB，206 可下载）。官方版无源码仓库；社区开源版 Producdevity/gamehub-lite（★2100）在本库另有条目。原记「原 GitHub 仓库已不可见」表述不准，实为闭源官方版从未开源",
        "community_sources": [
            {"repo": "https://github.com/Producdevity/gamehub-lite", "note": "开源 Lite 版 ★2100，本库另有条目"},
        ],
    },
    # Sudachi
    "sudachi-emulator": {
        "description": "Sudachi：Switch 模拟器。官网四个平台下载按钮全部指向 archive.org 存档分发",
        "repo_url": "https://sudachi-emulator.com",
        "homepage": "https://sudachi-emulator.com",
        "download_url": "https://archive.org/download/sudachiemulator-apk/sudachiemulator-apk.apk",
        "download_kind": "archive-org",
        "distribution": "official-site",
        "status": "stale",
        "note": "溯源结论：官网下载按钮无自有托管，全部指向 archive.org（安卓包 sudachiemulator-apk.apk，v1.0.16）。GitHub mbruno9/Sudachi（★30）只是 iPad 版壳工程，无源码无 release；上游 emuplace/sudachi.emuplace.app 已 404，jotesoft/winterwisperer/drizzt 全部 451 DMCA。无一手源码可用",
        "community_sources": [],
    },
    # Termux 系：全部指向各自真实分发地址
    "termux-jiaxinchen": {
        "repo_url": "https://github.com/termux/termux-app",
        "homepage": "https://f-droid.org/packages/com.termux/",
        "download_url": "https://f-droid.org/packages/com.termux/",
        "download_kind": "f-droid",
        "distribution": "f-droid",
        "note": "溯源结论：本条为 Termux 官方本体（非「增强镜像」）。官方 F-Droid 包 com.termux，源码 termux/termux-app（★64007），GitHub release v0.119.0-beta.3 附 15 个 APK 资产",
    },
    "termux-plus": {
        "repo_url": "https://github.com/thejaustin/TermuxPlus",
        "homepage": "https://github.com/thejaustin/TermuxPlus",
        "download_url": "https://github.com/thejaustin/TermuxPlus/releases/download/v0.118.0/termux-plus-release-0.118.0.apk",
        "download_kind": "github-release",
        "distribution": "release-apk",
        "note": "溯源结论：原记录错指 F-Droid 的 com.termux（那是官方本体）。TermuxPlus 自有仓库 thejaustin/TermuxPlus（★21，fork of termux/termux-app），release v0.118.0 附 APK",
    },
    "termux-enhanced": {
        "repo_url": "https://github.com/NIK2703/termux-enhanced",
        "homepage": "https://github.com/NIK2703/termux-enhanced",
        "download_url": "https://github.com/NIK2703/termux-enhanced/releases/download/Release22/termux-app_apt-android-7-release_arm64-v8a.apk",
        "download_kind": "github-release",
        "distribution": "release-apk",
        "note": "溯源结论：原记录错指 F-Droid 的 com.termux。自有仓库 NIK2703/termux-enhanced（★24），release Release22 附 8 个 APK 资产（含 arm64-v8a）",
    },
    "termux-aether": {
        "repo_url": "https://github.com/wallentx/termux-aether-app",
        "homepage": "https://github.com/wallentx/termux-aether-app",
        "download_url": "https://github.com/wallentx/termux-aether-app/releases/download/v1000.0.2/termux-app-aether-v1000.0.2-arm64-v8a.apk",
        "download_kind": "github-release",
        "distribution": "release-apk",
        "source": "github",
        "note": "溯源结论：原记录 source 标 official-web 且错指 F-Droid。实际自有仓库 wallentx/termux-aether-app（★9，fork of termux/termux-app），release v1000.0.2 附 APK 与源码 tarball",
    },
    "termux-zero": {
        "repo_url": "https://github.com/hanxinhao000/ZeroTermux",
        "homepage": "https://github.com/hanxinhao000/ZeroTermux",
        "download_url": "https://github.com/hanxinhao000/ZeroTermux/releases/download/ZeroTermux-0.118.3.64/termux-app_apt-android-7-release_arm64-v8a.apk",
        "download_kind": "github-release",
        "distribution": "release-apk",
        "source": "github",
        "note": "溯源结论：原记录 source 标 official-web 且错指 F-Droid。实际自有仓库 hanxinhao000/ZeroTermux（★3099），release ZeroTermux-0.118.3.64 附 4 个 APK；官方镜像 https://od.ixcmstudio.cn/repository/main/ZeroTermux/",
    },
    "termux-kotlin": {
        "repo_url": "https://github.com/reapercanuk39/termux-kotlin-app",
        "homepage": "https://github.com/reapercanuk39/termux-kotlin-app",
        "download_url": "https://github.com/reapercanuk39/termux-kotlin-app/releases/download/v2.0.14/termux-app_apt-android-7-release_arm64-v8a.apk",
        "download_kind": "github-release",
        "distribution": "release-apk",
        "source": "github",
        "note": "溯源结论：原记录 source 标 official-web 且错指 F-Droid。实际自有仓库 reapercanuk39/termux-kotlin-app（★72），release v2.0.14 附 10 个 APK 资产",
    },
    # Winlator Bionic cjxyz：原 repo_url 指向不存在的 winlator/releases
    "winlator-releases": {
        "description": "Winlator Bionic cjxyz 分支：brunodev85 → coffincolors → cjxyz 演进链上的 Bionic 版本",
        "owner": "cjxyz",
        "repo": "cjxyz/winlator",
        "repo_url": "https://github.com/cjxyz/winlator",
        "homepage": "https://winlator.org/",
        "source": "github",
        "official": False,
        "distribution": "release-apk",
        "status": "stale",
        "note": "溯源结论：原 repo_url 指向 github.com/winlator/releases（404），实为 cjxyz/winlator（★139，MIT）。release f0891a0 附 app-debug.apk，可直接下载。仓库最后提交 2025-03-28，已停更。官网 winlator.org 的 Download 菜单直接指 brunodev85/winlator/releases，站内无独立下载页",
        "latest_apk_name": "app-debug.apk",
        "latest_apk_url": "https://github.com/cjxyz/winlator/releases/download/f0891a0/app-debug.apk",
    },
    # Ryubing：repo_url 指向 GitHub Website，实际源码在自建 Forgejo
    "ryubing-emulator": {
        "description": "Ryubing：Ryujinx 的社区续接版本（Ryujinx 本体 2024-10 因 DMCA 停更后由社区接管维护）",
        "owner": "projects",
        "repo": "projects/Ryubing",
        "repo_url": "https://git.ryujinx.app/projects/Ryubing",
        "homepage": "https://ryujinx.app",
        "source": "gitea",
        "official": False,
        "distribution": "forgejo-release",
        "status": "active",
        "note": "溯源结论：原 repo_url 指向 github.com/Ryubing/Website（只是网站仓，非源码）。一手源码在自建 Forgejo https://git.ryujinx.app/projects/Ryubing（2026-11-02 仍提交），release 1.3.3 附 linux/macOS 包；日常 canary 构建由 Ryubing/Canary 仓每次提交发布（最新 1.3.352）。官网 ryujinx.app 的 src 链接即 302 到该 Forgejo 仓",
        "latest_tag": "1.3.3",
        "latest_date": "2025-10-11T00:00:00Z",
        "community_sources": [
            {"repo": "https://git.ryujinx.app/Ryubing/Canary", "note": "每次提交发版，最新 1.3.352"},
            {"repo": "https://git.ryujinx.app/archive/ryujinx-mirror", "note": "ryujinx-mirror 归档"},
        ],
    },
}

# ---------------------------------------------------------------------------
# 3. 新收录：有价值的续集/分支仓库
# ---------------------------------------------------------------------------
# 分两类：安卓原生可运行 / PC 移植与桌面分支（独立分类）
ADDITIONS = [
    # --- Switch 生态续作 ---
    {
        "id": "ryubing-canary",
        "name": "Ryubing Canary",
        "category": "Nintendo Switch Emulator",
        "description": "Ryubing 社区每次提交自动发版的 canary 构建通道",
        "owner": "Ryubing",
        "repo": "Ryubing/Canary",
        "repo_url": "https://git.ryujinx.app/Ryubing/Canary",
        "homepage": "https://ryujinx.app",
        "official": False,
        "channel": "canary",
        "latest_tag": "1.3.352",
        "latest_date": "2026-11-02T00:00:00Z",
        "latest_apk_name": "ryujinx-canary-1.3.352-arm64.AppImage",
        "latest_apk_url": "https://git.ryujinx.app/Ryubing/Canary/releases/download/1.3.352/ryujinx-canary-1.3.352-arm64.AppImage",
        "pushed_at": "2026-11-02T00:00:00Z",
        "stars": 1,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "https://t3st31.github.io/Ranking-Emulators-Download/logos/ryujinx.png",
        "releases": [],
        "source": "gitea",
        "distribution": "forgejo-release",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "active",
        "note": "Ryujinx 本体 2024-10 DMCA 停更后，社区 Ryubing 团队在自建 Forgejo 接管维护。本仓每次提交自动发版，含 arm64 AppImage / linux tar.gz / macOS 包。属正式 release 之外的补充通道",
    },
    {
        "id": "melonx-ios",
        "name": "MeloNX",
        "category": "Nintendo Switch Emulator",
        "description": "MeloNX：Ryujinx 的 iOS/iPadOS 移植，release 直接附 IPA",
        "owner": "projects",
        "repo": "projects/MeloNX",
        "repo_url": "https://git.ryujinx.app/projects/MeloNX",
        "homepage": "https://ryujinx.app",
        "official": False,
        "channel": "stable",
        "latest_tag": "2.6",
        "latest_date": "2026-08-26T00:00:00Z",
        "latest_apk_name": "MeloNX.ipa",
        "latest_apk_url": "https://git.ryujinx.app/projects/MeloNX/releases/download/2.6/MeloNX.ipa",
        "pushed_at": "2026-08-26T00:00:00Z",
        "stars": 4,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "https://t3st31.github.io/Ranking-Emulators-Download/logos/ryujinx.png",
        "releases": [],
        "source": "gitea",
        "distribution": "forgejo-release",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch", "ios"],
        "status": "active",
        "note": "Ryujinx 系 iOS 移植，release 附 MeloNX.ipa（2.6 / 2026-08-26）。iOS 侧签名需自行处理，非安卓渠道",
    },
    {
        "id": "ruzu-emu",
        "name": "Ruzu",
        "category": "Nintendo Switch Emulator",
        "description": "Ruzu：用 Rust 重写的 Switch 模拟器，实验性但独立实现、跨平台",
        "owner": "vricosti",
        "repo": "vricosti/ruzu-emu",
        "repo_url": "https://github.com/vricosti/ruzu-emu",
        "homepage": "",
        "official": False,
        "channel": "rc",
        "latest_tag": "v0.1.1-rc8",
        "latest_date": "2026-10-28T00:00:00Z",
        "latest_apk_name": "Ruzu-Linux-v0.1.1-rc8-x86_64.AppImage",
        "latest_apk_url": "https://github.com/vricosti/ruzu-emu/releases/download/v0.1.1-rc8/Ruzu-Linux-v0.1.1-rc8-x86_64.AppImage",
        "pushed_at": "2026-10-28T00:00:00Z",
        "stars": 198,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-apk",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "active",
        "note": "Rust 从零实现的 Switch 模拟器，非任何既有项目 fork，是独立技术路线。★198，GPL-3.0，2026-10-28 仍发版。release 附 linux AppImage / macOS zip / deb / SteamDeck 变体。成熟度仍为 rc",
    },
    {
        "id": "eden-nx2-zuan",
        "name": "Eden NX2 (ZuanCrisp)",
        "category": "Nintendo Switch Emulator",
        "description": "Eden 的 Switch 2 实验分支，release 附 Windows 构建与校验和",
        "owner": "ZuanCrisp",
        "repo": "ZuanCrisp/Eden-NX2",
        "repo_url": "https://github.com/ZuanCrisp/Eden-NX2",
        "homepage": "",
        "official": False,
        "channel": "experimental",
        "latest_tag": "nx2-2026-10-29",
        "latest_date": "2026-10-31T00:00:00Z",
        "latest_apk_name": "Eden-Windows-nx2-2026-10-29-amd64-msvc-standard.zip",
        "latest_apk_url": "https://github.com/ZuanCrisp/Eden-NX2/releases/download/nx2-2026-10-29/Eden-Windows-nx2-2026-10-29-amd64-msvc-standard.zip",
        "pushed_at": "2026-11-02T00:00:00Z",
        "stars": 40,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "experimental",
        "note": "Switch 2 实验支持分支，与本库已有的 Eden SX2A（安卓侧）路线不同，本条为 Windows 桌面分支。★40，GPL-3.0，release 附 SHA256SUMS",
    },
    {
        "id": "prospero-eden-encore",
        "name": "ProsperoEden Encore",
        "category": "PS5 Port",
        "description": "ProsperoEden Encore：Eden 的 Switch 1 + Switch 2 实验分支",
        "owner": "niakw",
        "repo": "niakw/ProsperoEden-Encore",
        "repo_url": "https://github.com/niakw/ProsperoEden-Encore",
        "homepage": "",
        "official": False,
        "channel": "experimental",
        "latest_tag": "encore-R1-56293953",
        "latest_date": "2026-10-30T00:00:00Z",
        "latest_apk_name": "",
        "latest_apk_url": "",
        "pushed_at": "2026-11-03T00:00:00Z",
        "stars": 79,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "experimental",
        "note": "★79，2026-11-03 仍活跃。release 附可运行构建。与本库已有 ProsperoEden（Android 移植）同源不同目标平台",
    },
    # --- 其他平台的高价值续作/分支 ---
    {
        "id": "dosbox-staging",
        "name": "DOSBox Staging",
        "category": "PC 移植",
        "description": "DOSBox 的官方现代化延续分支（modern continuation），多平台原生构建",
        "owner": "dosbox-staging",
        "repo": "dosbox-staging/dosbox-staging",
        "repo_url": "https://github.com/dosbox-staging/dosbox-staging",
        "homepage": "https://dosbox-staging.org/",
        "official": True,
        "channel": "stable",
        "latest_tag": "v0.83.0",
        "latest_date": "2026-08-27T00:00:00Z",
        "latest_apk_name": "dosbox-staging-windows-x64-v0.83.0-setup.exe",
        "latest_apk_url": "https://github.com/dosbox-staging/dosbox-staging/releases/download/v0.83.0/dosbox-staging-windows-x64-v0.83.0-setup.exe",
        "pushed_at": "2026-11-03T00:00:00Z",
        "stars": 1834,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["windows-pc", "multi"],
        "status": "active",
        "note": "DOSBox 官方承认的现代延续分支，★1834，2026-11-03 仍提交。正式版 v0.83.0 附 Windows/Linux/macOS 原生包，dev-latest 通道附每日构建",
    },
    {
        "id": "dosbox-x",
        "name": "DOSBox-X",
        "category": "PC 移植",
        "description": "DOSBox-X：老牌增强分支，支持 DOS 盒装游戏长期保存与多系统扩展",
        "owner": "joncampbell123",
        "repo": "joncampbell123/dosbox-x",
        "repo_url": "https://github.com/joncampbell123/dosbox-x",
        "homepage": "https://dosbox-x.com/",
        "official": False,
        "channel": "stable",
        "latest_tag": "dosbox-x-v2026.10.01",
        "latest_date": "2026-10-02T00:00:00Z",
        "latest_apk_name": "dosbox-x-mingw32-2026.10.01-portable.zip",
        "latest_apk_url": "https://github.com/joncampbell123/dosbox-x/releases/download/dosbox-x-v2026.10.01/dosbox-x-mingw32-2026.10.01-portable.zip",
        "pushed_at": "2026-10-29T00:00:00Z",
        "stars": 3804,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["windows-pc", "multi"],
        "status": "active",
        "note": "★3804，2026-10-29 仍提交。release 附 Windows/macOS/Linux 可移植包",
    },
    {
        "id": "dosbox-pure",
        "name": "DOSBox Pure",
        "category": "PC 移植",
        "description": "DOSBox Pure：专为 libretro/RetroArch 打造的重写版，含 Android arm64 核心包",
        "owner": "schellingb",
        "repo": "schellingb/dosbox-pure",
        "repo_url": "https://github.com/schellingb/dosbox-pure",
        "homepage": "",
        "official": False,
        "channel": "preview",
        "latest_tag": "1.0-preview6",
        "latest_date": "2026-07-14T00:00:00Z",
        "latest_apk_name": "dosbox_pure_libretro-android_arm64-1.0-preview6.zip",
        "latest_apk_url": "https://github.com/schellingb/dosbox-pure/releases/download/1.0-preview6/dosbox_pure_libretro-android_arm64-1.0-preview6.zip",
        "pushed_at": "2026-10-06T00:00:00Z",
        "stars": 1185,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "libretro",
        "platforms": ["windows-pc", "multi"],
        "status": "active",
        "note": "★1185，GPL-2.0。非 fork 而是重写，专为 RetroArch/libretro 后端设计。release 1.0-preview6 附 android arm32/arm64 核心包，可直接供安卓端 RetroArch 使用",
    },
    {
        "id": "flycast-dojo",
        "name": "Flycast Dojo",
        "category": "Sega Dreamcast",
        "description": "Flycast Dojo：Flycast 的社区增强分支，持续维护",
        "owner": "blueminder",
        "repo": "blueminder/flycast-dojo",
        "repo_url": "https://github.com/blueminder/flycast-dojo",
        "homepage": "",
        "official": False,
        "channel": "stable",
        "latest_tag": "dojo-7.9",
        "latest_date": "2026-10-24T00:00:00Z",
        "latest_apk_name": "flycast-dojo-windows.zip",
        "latest_apk_url": "https://github.com/blueminder/flycast-dojo/releases/download/dojo-7.9/flycast-dojo-windows.zip",
        "pushed_at": "2026-10-24T00:00:00Z",
        "stars": 246,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["dreamcast"],
        "status": "active",
        "note": "★246，2026-10-24 仍发版。release 附 Windows/macOS/Linux 包。属 Dreamcast 方向的高价值活跃分支",
    },
    {
        "id": "snes9x-z",
        "name": "Snes9x-Z",
        "category": "Super Nintendo Emulator",
        "description": "Snes9x-Z：Snes9x 的性能优化分支，提供 libretro 核心（含安卓 arm64）",
        "owner": "andrezaiats",
        "repo": "andrezaiats/snes9x-Z",
        "repo_url": "https://github.com/andrezaiats/snes9x-Z",
        "homepage": "",
        "official": False,
        "channel": "stable",
        "latest_tag": "v1.63-z1",
        "latest_date": "2026-09-11T00:00:00Z",
        "latest_apk_name": "snes9x-Z_libretro_android-arm64-v8a.zip",
        "latest_apk_url": "https://github.com/andrezaiats/snes9x-Z/releases/download/v1.63-z1/snes9x-Z_libretro_android-arm64-v8a.zip",
        "pushed_at": "2026-09-14T00:00:00Z",
        "stars": 103,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "libretro",
        "platforms": ["snes"],
        "status": "active",
        "note": "★103。基于 Snes9x v1.63 的性能优化 fork，release 直接提供 libretro 核心包，含 android arm64-v8a 与 armeabi-v7a",
    },
    {
        "id": "mupen64plus-ae-fzurita",
        "name": "Mupen64Plus AE (fzurita)",
        "category": "Nintendo 64 Emulator",
        "description": "Mupen64Plus AE 的 fzurita 分支，安卓端 M64Plus FZ 商店版所对应的开源核心",
        "owner": "fzurita",
        "repo": "fzurita/mupen64plus-ae",
        "repo_url": "https://github.com/fzurita/mupen64plus-ae",
        "homepage": "",
        "official": False,
        "channel": "stable",
        "latest_tag": "",
        "latest_date": "",
        "latest_apk_name": "",
        "latest_apk_url": "",
        "pushed_at": "2024-04-02T00:00:00Z",
        "stars": 27,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "source-only",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["n64"],
        "status": "stale",
        "note": "★27，GPL-3.0。Play 商店版 M64Plus FZ（包名 org.mupen64plusae.v3.fzurita）对应的开源核心仓，本仓无 release，需自行编译。fork 自 mupen64plus-ae/mupen64plus-ae（★504）",
    },
    {
        "id": "ryudeck-steamdeck",
        "name": "Ryujinx Ryudeck",
        "category": "Nintendo Switch Emulator",
        "description": "Ryudeck：面向 Steam Deck 原生的 Ryujinx 分支，针对掌机做适配",
        "owner": "deucebucket",
        "repo": "deucebucket/ryudeck",
        "repo_url": "https://github.com/deucebucket/ryudeck",
        "homepage": "",
        "official": False,
        "channel": "stable",
        "latest_tag": "v0.1.24",
        "latest_date": "2026-07-15T00:00:00Z",
        "latest_apk_name": "ryudeck-v0.1.24-linux_x64.tar.gz",
        "latest_apk_url": "https://github.com/deucebucket/ryudeck/releases/download/v0.1.24/ryudeck-v0.1.24-linux_x64.tar.gz",
        "pushed_at": "2026-07-19T00:00:00Z",
        "stars": 11,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "active",
        "note": "★11，MIT。Steam Deck 原生 Ryujinx 分支，release 附 linux tar.gz。低热度但方向独特（掌机原生适配），予以收录",
    },
    {
        "id": "ryujinx-canary-ademola",
        "name": "Ryujinx Canary Builds (ADEMOLA200)",
        "category": "Nintendo Switch Emulator",
        "description": "Ryujinx 的社区 canary 构建仓，每次提交编译发布",
        "owner": "ADEMOLA200",
        "repo": "ADEMOLA200/Ryujinx-Canary-Builds",
        "repo_url": "https://github.com/ADEMOLA200/Ryujinx-Canary-Builds",
        "homepage": "",
        "official": False,
        "channel": "canary",
        "latest_tag": "1.3.31",
        "latest_date": "2025-04-17T00:00:00Z",
        "latest_apk_name": "ryujinx-canary-1.3.31-arm64.AppImage",
        "latest_apk_url": "https://github.com/ADEMOLA200/Ryujinx-Canary-Builds/releases/download/1.3.31/ryujinx-canary-1.3.31-arm64.AppImage",
        "pushed_at": "2025-04-17T00:00:00Z",
        "stars": 21,
        "forks": 0,
        "watchers": 0,
        "downloads": 0,
        "logo_url": "",
        "releases": [],
        "source": "github",
        "distribution": "release-zip",
        "kind": "emulator",
        "runtime": "desktop",
        "platforms": ["switch"],
        "status": "stale",
        "note": "★21，MIT。Ryujinx 本体停更前的社区 canary 构建仓，最后发版 2025-04-17，已停更。当前活跃的 canary 通道是 Forgejo 上的 Ryubing/Canary",
    },
]


def apply(reg, eid, patch):
    """把 patch 合并到目标条目。清空语义用空字符串。"""
    for e in reg["emulators"]:
        if e.get("id") == eid:
            for k, v in patch.items():
                e[k] = v
            return True
    return False


def main():
    with open(REG, encoding="utf-8") as f:
        reg = json.load(f)

    before = len(reg["emulators"])
    changed, missing = [], []

    for eid, patch in list(COMMUNITY_FIX.items()) + list(CLOSED_FIX.items()):
        if apply(reg, eid, patch):
            changed.append(eid)
        else:
            missing.append(eid)

    existing_ids = {e.get("id") for e in reg["emulators"]}
    added = []
    for item in ADDITIONS:
        if item["id"] in existing_ids:
            missing.append(item["id"] + "(已存在)")
            continue
        # 补齐 schema 字段，保证与既有条目一致
        full = {
            "id": "", "name": "", "category": "", "description": "", "owner": "",
            "repo": "", "homepage": "", "repo_url": "", "official": False,
            "channel": "stable", "latest_tag": "", "latest_date": "",
            "latest_body": "", "latest_apk_name": "", "latest_apk_url": "",
            "latest_apk_size": 0, "pushed_at": "", "stars": 0, "forks": 0,
            "watchers": 0, "downloads": 0, "logo_url": "", "releases": [],
            "source": "github", "distribution": "", "kind": "emulator",
            "runtime": "android-native", "platforms": [], "status": "active",
            "note": "",
        }
        full.update(item)
        reg["emulators"].append(full)
        added.append(item["id"])

    reg["updated_at"] = "2026-11-03"
    ver = str(reg.get("version", "1.0.0"))
    parts = ver.split(".")
    if len(parts) == 3 and all(p.isdigit() for p in parts):
        parts[2] = str(int(parts[2]) + 1)
        reg["version"] = ".".join(parts)

    with open(REG, "w", encoding="utf-8") as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"条目数 {before} -> {len(reg['emulators'])}")
    print(f"修正 {len(changed)} 条: {', '.join(changed)}")
    print(f"新增 {len(added)} 条: {', '.join(added)}")
    if missing:
        print(f"未命中 {len(missing)} 条: {', '.join(missing)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
