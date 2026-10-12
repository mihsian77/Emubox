package com.emubox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val DEFAULT_EMULATOR_REGISTRY_URL =
    "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/emulator-registry.json"

const val DEFAULT_EMULATOR_RANKING_URL =
    "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/emulator-ranking.json"

/** 模拟器条目：官方 registry（版本/APK 直链）+ 社区排行（下载量/更新日志）合并 */
data class EmulatorEntry(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val owner: String,
    val repo: String,
    val homepage: String = "",
    val latestTag: String = "",
    val latestApkUrl: String = "",
    val pushedAt: String = "",
    val stars: Long = 0L,
    val downloads: Long = 0L,
    val latestDate: String = "",
    val latestBody: String = ""
) {
    val githubUrl: String
        get() = if (owner.isNotBlank() && repo.isNotBlank()) "https://github.com/$owner/$repo" else ""

    /** 社区排行条目（无官方 APK 直链，走 GitHub Release / 官网） */
    val hasApk: Boolean get() = latestApkUrl.isNotBlank()
}

object EmulatorRegistryRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 内置清单：官方 registry 9 条 + 社区排行精选 20 条（离线即有完整数据，远程每日刷新覆盖） */
    fun builtInRegistry(): List<EmulatorEntry> = listOf(
        EmulatorEntry(
            id = "winlator", name = "Winlator", category = "windows",
            description = "安卓端 Windows x86 转译运行环境，通过 Wine/Box64 运行 Windows 游戏与软件，EmuBox 驱动/组件的主要使用场景",
            owner = "brunodev85", repo = "winlator", homepage = "",
            latestTag = "v11.2.0", latestApkUrl = "https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk",
            pushedAt = "2026-09-22T18:36:59Z", stars = 19419L, downloads = 16072830L,
            latestDate = "2026-08-19",
            latestBody = "**v11.2 (Latest)**\r\n\r\n- Added an option to save memory on run games from Steam\r\n- Improved the presentation of rendered images\r\n- Implemented XInput extension for raw input\r\n- Implemented an option to run Winlator as foreground service (by @Juan-Antonio-Doe)\r\n- Update Turnip driver\r\n- Update DXVK, DXVK GPLASYNC variants to latest versions\r\n- Fixed a bug with exp"
        ),
        EmulatorEntry(
            id = "gamehub", name = "GameHub", category = "windows",
            description = "面向安卓的 Windows 游戏整合平台，内置 Wine/Box64 兼容层，可安装 DXVK、Turnip 等组件",
            owner = "", repo = "", homepage = "https://gamehub.com",
            latestTag = "", latestApkUrl = "",
            pushedAt = "", stars = 0L
        ),
        EmulatorEntry(
            id = "eden", name = "Eden", category = "windows",
            description = "Winlator 衍生分支，部分驱动仓库（如 s1mptom）专门针对其在 Adreno 830/840 上的运行适配",
            owner = "", repo = "", homepage = "",
            latestTag = "", latestApkUrl = "",
            pushedAt = "", stars = 0L
        ),
        EmulatorEntry(
            id = "termux", name = "Termux", category = "terminal",
            description = "安卓终端模拟器与 Linux 环境，可安装 Box64 等转译层，是驱动/组件的进阶使用场景",
            owner = "termux", repo = "termux-app", homepage = "",
            latestTag = "v0.119.0-beta.3", latestApkUrl = "https://github.com/termux/termux-app/releases/download/v0.119.0-beta.3/termux-app_v0.119.0-beta.3%2Bapt-android-5-github-debug_arm64-v8a.apk",
            pushedAt = "2026-09-26T05:11:44Z", stars = 62426L
        ),
        EmulatorEntry(
            id = "ppsspp", name = "PPSSPP", category = "console",
            description = "PSP 模拟器，支持 Vulkan/OpenGL 渲染，安卓端成熟的掌机模拟方案",
            owner = "hrydgard", repo = "ppsspp", homepage = "",
            latestTag = "v1.20.4", latestApkUrl = "",
            pushedAt = "2026-10-12T07:50:54Z", stars = 14643L
        ),
        EmulatorEntry(
            id = "retroarch", name = "RetroArch", category = "multi",
            description = "多机种聚合模拟器前端，内置大量核心（核心通过在线下载），支持 Vulkan 渲染",
            owner = "libretro", repo = "RetroArch", homepage = "",
            latestTag = "v1.22.2", latestApkUrl = "",
            pushedAt = "2026-10-12T00:31:36Z", stars = 14300L
        ),
        EmulatorEntry(
            id = "dolphin", name = "Dolphin", category = "console",
            description = "GameCube/Wii 模拟器，安卓端 Vulkan 支持成熟",
            owner = "dolphin-emu", repo = "dolphin", homepage = "",
            latestTag = "nJoy", latestApkUrl = "",
            pushedAt = "2026-10-12T04:41:55Z", stars = 15649L
        ),
        EmulatorEntry(
            id = "duckstation", name = "DuckStation", category = "console",
            description = "PS1 模拟器，兼容性与画质增强优秀，支持 Vulkan",
            owner = "stenzek", repo = "duckstation", homepage = "",
            latestTag = "preview", latestApkUrl = "",
            pushedAt = "2026-10-12T02:11:06Z", stars = 10824L
        ),
        EmulatorEntry(
            id = "vita3k", name = "Vita3K", category = "console",
            description = "PSVita 模拟器，安卓端实验性支持 Vulkan",
            owner = "Vita3K", repo = "Vita3K", homepage = "",
            latestTag = "continuous", latestApkUrl = "https://github.com/Vita3K/Vita3K/releases/download/continuous/android-latest.apk",
            pushedAt = "2026-10-11T13:42:17Z", stars = 5818L
        ),
        EmulatorEntry(
            id = "brunodev85-winlator", name = "Winlator BrunoDev", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "brunodev85", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "v11.2.0", latestApkUrl = "",
            pushedAt = "2026-09-22T18:36:59Z", stars = 19417L, downloads = 16072830L,
            latestDate = "2026-08-19", latestBody = "**v11.2 (Latest)**\n\n- Added an option to save memory on run games from Steam\n- Improved the presentation of rendered images\n- Implemented XInput extension for raw input\n- Implemented an option to run Winlator as foreground service (by @Juan-Antonio-Doe)\n- Updated Mesa Turnip (v26.2.0 from @whitebelyash branch)\n\n**v11.2 (Beta)**\n- Fixed gamepad input lag\n- Added Box64 v0.4.4 (release)\n- Improved Vortek:\n	└─ Improved texture decoding (it was not handling mipmaps correctly)\n	└─ Some improvements were added for the Xclipse GPU\n- Improved Gladio:\n	└─ Added a better way to handle acti"
        ),
        EmulatorEntry(
            id = "azahar-emu-azahar", name = "Azahar", category = "console",
            description = "An open-source 3DS emulator project based on Citra.",
            owner = "azahar-emu", repo = "azahar", homepage = "https://azahar-emu.org/",
            latestTag = "2127.0-alpha1", latestApkUrl = "",
            pushedAt = "2026-10-10T13:45:51Z", stars = 8324L, downloads = 8611904L,
            latestDate = "2026-10-10", latestBody = "<h3>⚠️Important Alpha Information⚠️</h3>\n\nAs an alpha release, this build is not yet considered stable.\n\nThis prerelease is intended for users to test upcoming features, allowing the community to provide feedback before they are distributed in a stable release.\nUsers interested in testing bleeding-edge software are encouraged to test out this build to help us catch any issues we may have missed during development.\n\nIf you are unwilling to tolerate potential issues, it may be better to wait for the next stable release.\n\n---\n\n> [!NOTE] \n> To allow public testing of the new interprete"
        ),
        EmulatorEntry(
            id = "weihuoya-citra", name = "Citra (weihuoya)", category = "console",
            description = "A Nintendo 3DS Emulator",
            owner = "weihuoya", repo = "citra", homepage = "https://citra-emu.org",
            latestTag = "20251112", latestApkUrl = "",
            pushedAt = "2025-11-14T11:04:59Z", stars = 2762L, downloads = 8058376L,
            latestDate = "2025-11-12", latestBody = "**Before:**\nemulator directory: \"citra-emu\"\n\n**Antutu**:\npackage name: com.antutu.ABenchMark\nThis is tricking the os thinks it's running a benchmark app, and will boost performance on some device.\n\n**Storage Access:**\nemulator directory: \"Android/data/org.citra.emu/files/citra-emu\"\nWARNING uninstall citra will remove directory, include games, dlcs, save datas.\n\ndirectory: \"citra-emu\nand\\data\\00000000000000000000000000000000\\sysdata\\00010026\\00000000\\CEC\"\nDelete this directory if game could not works as before.\n\n**Try reset settings** if game does not work properly."
        ),
        EmulatorEntry(
            id = "Vita3K-Vita3K-Android", name = "Vita3K Android", category = "console",
            description = "Public releases and CI for the Vita3K Android app",
            owner = "Vita3K", repo = "Vita3K-Android", homepage = "None",
            latestTag = "v12", latestApkUrl = "",
            pushedAt = "2025-02-22T16:30:58Z", stars = 1666L, downloads = 4327222L,
            latestDate = "2024-06-06", latestBody = "Vita3K Android release 12 Changelog (non exhaustive list):\n- SD card support: Allow any folder to be specified as the OS/game data folder (inspired by the PR from nurunuruslime)\n- Allow the overlay to be used at the same time as the touchscreen\n- Fix black bands appearing sometimes at the top and bottom of the application on some devices\n- Fix bug causing game installation to be 10x slower than expected\n- Fix accelerometer and gyroscope on android game consoles\n- Some crash fixes\n- Add controller opacity (dima)\n\n\n\nRebase on PC version:\n- Fix audio regression (bookmist)\n- Code clea"
        ),
        EmulatorEntry(
            id = "Trixarian-NetherSX2-patch", name = "NetherSX2 Patch", category = "console",
            description = "Continuation of NetherSX2 based on AetherSX2 4248",
            owner = "Trixarian", repo = "NetherSX2-patch", homepage = "https://discord.gg/xRh3NnYyMa",
            latestTag = "2.2n", latestApkUrl = "",
            pushedAt = "2026-09-22T04:21:34Z", stars = 2679L, downloads = 3501608L,
            latestDate = "2025-12-25", latestBody = "Merry Christmas, Happy Holidays and a Happy New Year everyone!\n\nThis update adds experimental Online Multiplayer to NetherSX2 along with the usual updates!\nThanks to [IamLupo](https://github.com/IamLupo) for adding it to ARMSX2, and [zakilj3](https://www.youtube.com/user/zakilj3) for discovering the same settings work with NetherSX2!\n\n[VirusTotal Scan Report](https://www.virustotal.com/gui/file/2029e30dcc1ba1ecb8d73313f18b429b40e6044c7785c52af08b948ef94b3ddf)\n\n## How to use Online Multiplayer\n1. Go to your App Settings --> Settings and toggle on Enable DEV9 Ethernet\n2. Set API to Sock"
        ),
        EmulatorEntry(
            id = "SSimco-Cemu", name = "Cemu", category = "console",
            description = "Android port for the cemu emulator",
            owner = "SSimco", repo = "Cemu", homepage = "None",
            latestTag = "0.5", latestApkUrl = "",
            pushedAt = "2026-09-12T17:30:42Z", stars = 1205L, downloads = 2873874L,
            latestDate = "2026-04-25", latestBody = "Experimental release 0.5\n\n- Implemented options to enable emulated USB devices & configure them"
        ),
        EmulatorEntry(
            id = "StevenMXZ-Winlator-Ludashi", name = "Winlator Ludashi", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "StevenMXZ", repo = "Winlator-Ludashi", homepage = "https://winlator.org",
            latestTag = "v4.1", latestApkUrl = "",
            pushedAt = "2026-10-07T21:29:08Z", stars = 1225L, downloads = 1528735L,
            latestDate = "2026-09-13", latestBody = "What's new:\n\n\\- Fixed some games showing a black screen due to incorrect surface format. (like RDR2)\n\\- Updated DisplayX.\n\\- Now works in container mode.\n\\- Added two new options: submit every buffer and precise presentation.\n\\- added D7VK\n\\- Fixed X11 bypass not working on DX12.\n\\- improved Freedreno compatibility \n\\- Various bug fixes, improved stability, and some UI changes based on feedback.\n\n📌 update requires reinstallation"
        ),
        EmulatorEntry(
            id = "Producdevity-gamehub-lite", name = "GameHub Lite (Producdevity)", category = "windows",
            description = "GameHub Lite is a community-maintained modified version of GameHub for education purposes",
            owner = "Producdevity", repo = "gamehub-lite", homepage = "https://www.emuready.com/",
            latestTag = "v5.1.8", latestApkUrl = "",
            pushedAt = "2026-07-03T18:28:52Z", stars = 2041L, downloads = 1478561L,
            latestDate = "2026-07-02", latestBody = "## Security: Steam token redaction in logs\n\nGameHub Lite 5.1.8 fixes a logging issue where Steam authentication-related values could appear in diagnostic logs. \nIn some cases, logs could include fields such as `steamToken`, `refreshToken`, or `accessToken` from Steam login/launch flows.\n\n**Publicly posted logs containing these fields should be deleted or redacted.**\n\n### IMPORTANT\n\nThis should go without saying but If you find any security issues, please reach out **PRIVATELY**. \nI have not checked if this issue is still present in recent GameHub versions, already ruined my one day o"
        ),
        EmulatorEntry(
            id = "longjunyu2-winlator", name = "Winlator Glibc", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "longjunyu2", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "v7.1.5", latestApkUrl = "",
            pushedAt = "2026-01-05T23:43:15Z", stars = 551L, downloads = 1023386L,
            latestDate = "2025-01-25", latestBody = "## Warning! This APK is signed by a different key. This means you will have to uninstall the 7.1.3 version first (which will delete all your data!)\n\n## What's New\n#### Added\n- Alsa sound driver updated #183\n- Multikey touch button binding #197\n- Touch haptic when virtual keyboard or controller is enabled #62\n  \n#### Fixed\n- Invisible mouse cursor in some games #192\n- Some 32 bit games not working properly #173\n  \n####  What's new about OpenXR Support?\n- Fullscreen is forced only in immersive VR mode\n- Set Alsa as default audio driver"
        ),
        EmulatorEntry(
            id = "ARMSX2-ARMSX2", name = "ARMSX2", category = "console",
            description = "ARMSX2 - The Playstation 2 Emulator for ARM64 Platforms",
            owner = "ARMSX2", repo = "ARMSX2", homepage = "https://armsx2.net/",
            latestTag = "2.8.3", latestApkUrl = "",
            pushedAt = "2026-10-11T21:12:33Z", stars = 2159L, downloads = 923463L,
            latestDate = "2026-10-11", latestBody = "# ARMSX2 2.8.3\n\nWhat's new: \n\n## MaliSX2 for Mali GPUs\n\nMaliSX2 is a Vulkan driver for Mali GPUs that @bmdhacks built for ARMSX2. In 2.8.2 it was offered only on the Mali-G615 and G715, under its old name, libmali. Now it also covers the Mali-G57, G68, G77 and G78 (including the G78AE), which a lot of handhelds use, the Anbernic RG 476H among them.\n\n<img width=\"2340\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/884734d3-d2b8-4651-a87c-1149d34c2c9b\" />\n\nWhat it does for a game: on @DIB1998 Mali G57, Ace Combat 4 and Ace Combat 5 were unplayable on the stoc"
        ),
        EmulatorEntry(
            id = "flyinghead-flycast", name = "Flycast", category = "console",
            description = "Flycast is a multiplatform Sega Dreamcast, Naomi, Naomi 2 and Atomiswave emulator",
            owner = "flyinghead", repo = "flycast", homepage = "None",
            latestTag = "v2.7", latestApkUrl = "",
            pushedAt = "2026-10-10T01:44:52Z", stars = 2587L, downloads = 880608L,
            latestDate = "2026-08-19", latestBody = "## New features\n* Frame rate detection and pacing\n* Localization (fr, hu, jp, pt_BR, sv)\n* Multiscreen support for Airline Pilot, Sega Strike Fighter and Derby Owners Club\n* Improved modem emulation\n* improved DreamPicoPort and DreamPotato support by @RikkiGibson, @Tails86 and @nexus382\n* Android: Google Auto Backup support for VMU saves and non-volatile memory\n\n## Fixed Games\n* Airline Pilot (Naomi, multiscreen)\n* Cyber Troopers Virtual-On: Oratorio Tangram (JP, online features)\n* Derby Owners Club (new)\n* Dinosaur King (ZH, new)\n* DreamParaPara by @dakrk (new)\n* F355 Challenge "
        ),
        EmulatorEntry(
            id = "RPCSX-rpcsx-ui-android", name = "RPCSX Android", category = "console",
            description = "",
            owner = "RPCSX", repo = "rpcsx-ui-android", homepage = "None",
            latestTag = "v20250425", latestApkUrl = "",
            pushedAt = "2026-08-29T13:27:19Z", stars = 1330L, downloads = 851205L,
            latestDate = "2025-04-25", latestBody = "**Full Changelog**: https://github.com/RPCSX/rpcsx-ui-android/compare/v20250417...v20250425"
        ),
        EmulatorEntry(
            id = "coffincolors-winlator", name = "Winlator Coffincolors", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "coffincolors", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "cmod_v13.1", latestApkUrl = "",
            pushedAt = "2025-12-09T22:36:49Z", stars = 1115L, downloads = 844495L,
            latestDate = "2025-08-07", latestBody = "_Minor update v13.1.1 fixed custom icons, default renderer, and warning prompt handling on new installs_ \n\n------------------------------\n\n-   **`evshim` Rewrite:** The entire controller shim (`libevshim.so`) has been rewritten, **completely eliminating the use of shared memory (`mmap`)**, resulting in a massive reduction in CPU overhead and a significant performance boost, especially on lower-end devices. Box64 3.2 and 3.6 are now compatible, and have been restored.\n\n-   **ALSA-Reflector Improved** The pacer's ring buffer and reconnection logic have been refined, expect better performan"
        ),
        EmulatorEntry(
            id = "afeimod-winlator-mod", name = "Winlator Afei", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "afeimod", repo = "winlator-mod", homepage = "https://winlator.org",
            latestTag = "10.1hotfix-Amod3fix2", latestApkUrl = "",
            pushedAt = "2026-04-19T02:53:06Z", stars = 698L, downloads = 820488L,
            latestDate = "2025-12-02", latestBody = "1-修复部分游戏mod失效，比如死或生5，上古卷轴五等\n2-提供wine-10.15.whp可以在额外内容里安装，这个wine可以播放一些视频白屏有声音的游戏\n3-其他更新见fix1\n"
        ),
        EmulatorEntry(
            id = "Medard22-Dolphin-MMJR2-VBI", name = "Dolphin MMJR2 VBI", category = "console",
            description = "A fork of Dolphin MMJR2 - with added VBI Skip hack and further fixes ported over from Dolphin Official.",
            owner = "Medard22", repo = "Dolphin-MMJR2-VBI", homepage = "None",
            latestTag = "U24.07.02-2407", latestApkUrl = "",
            pushedAt = "2025-08-03T09:27:46Z", stars = 278L, downloads = 773815L,
            latestDate = "2025-07-20", latestBody = "Notable changes from U24.03.22-21259\n\n- Code refactoring and minor fixes\n- Fixed a panic alert for Def Jam: Fight for New York and Fifa Street\n- Numerous small optimizations to JitARM64\n- Small optimizations to DSP HLE audio decoding\n- Fixed stuttering from JIT Cache clear - mostly notable in Shadow the Hedgehog\n- Set OSD message color to yellow in default AMOLED theme\n- Updated Vulkan Memory Allocator and Vulkan Headers\n- Updated and optimized app icons\n\nMissing features - these features from Dolphin Official are not implemented\n\n- Android Input Overhaul and subsequent changes to"
        ),
        EmulatorEntry(
            id = "xodiosx-XoDos", name = "XoDos", category = "windows",
            description = "",
            owner = "xodiosx", repo = "XoDos", homepage = "None",
            latestTag = "v6.0.0+c050ebd", latestApkUrl = "",
            pushedAt = "2026-05-04T03:29:50Z", stars = 740L, downloads = 755643L,
            latestDate = "2025-11-09", latestBody = "**XoDos-6.0.0.r4 APKs**\n💝 Install over the old APK to UPDATE \n\n🎆Happy holidays 🎊\nThis going to be the last update for the development would be paused until further notice thanks for everyone support this project was a blessing, because All those who Loved and support it till today,, and maybe if google Changed it's 2026 policy against developers we can see more and more awesome projects from GitHub and other development places to improve android community and make it great again,,,♥️\n \n✅Fixed shortcuts manger to delete shortcuts\n✅ Removed PIP mode (picture in picture) it's crashing for "
        ),
        EmulatorEntry(
            id = "Fcharan-WinlatorMali", name = "Winlator Mali", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "Fcharan", repo = "WinlatorMali", homepage = "https://winlator.org",
            latestTag = "bionic-mali-1.1", latestApkUrl = "",
            pushedAt = "2026-10-04T12:44:25Z", stars = 440L, downloads = 640224L,
            latestDate = "2026-07-09", latestBody = "Winlator Mali Bionic v1.1\n\n🚀 New Features\n\n- Added Fake HDR.\n- Added a Universal FPS Limiter.\n- Added a Sharpness option.\n- Added GTA V Optimization toggle in Shortcut Settings.\n- Added DXVK Config toggle with built-in pre-made configuration presets.\n- Automatically generates a dxvk.conf file for each container based on the selected preset.\n- Added new DXVK versions.\n- Added the new GameNative Vulkan Wrapper.\n- Added new Box64 and FEX presets.\n\n---\n\n🎮 Unreal Engine 3 Preset\n\n- Added a dedicated Unreal Engine 3 preset.\n- Tested with X-Men Origins: Wolverine for improved compa"
        ),
        EmulatorEntry(
            id = "HorizonEmuTeam-Horizon-Emu", name = "Horizon Emu", category = "windows",
            description = "",
            owner = "HorizonEmuTeam", repo = "Horizon-Emu", homepage = "None",
            latestTag = "v4", latestApkUrl = "",
            pushedAt = "2025-02-28T07:48:56Z", stars = 717L, downloads = 486738L,
            latestDate = "2025-02-28", latestBody = "After minor changes like Box64, Wine (all have almost the same performance), DXVK and Turnip and me not having time to do anything with this project, it's finally getting a \"Not Connected\" fix related to the usage of /tmp directory on some devices by the termux-x11 library in the app. Should work on Android 15 with this fix as well, people already reported it working"
        ),
        EmulatorEntry(
            id = "The412Banner-bannerhub", name = "BannerHub (The412Banner)", category = "windows",
            description = "GameHub ReVanced — extended with GOG, Epic & Amazon game libraries, Component Manager, Winlator HUD, per-game controls & configs, and more. Built via apktool smali patching.",
            owner = "The412Banner", repo = "bannerhub", homepage = "None",
            latestTag = "v3.8.1", latestApkUrl = "",
            pushedAt = "2026-09-28T21:24:36Z", stars = 529L, downloads = 363297L,
            latestDate = "2026-09-12", latestBody = "> [!WARNING]\n> **Only install official stable releases** (like this one). Do NOT install pre-releases or raw CI artifacts — pre-release builds use a different package name (`com.tencent.ig`) and cannot be upgraded to stable without uninstalling first.\n\n> [!IMPORTANT]\n> **Microphone permission required for Voice Chat.** When you activate voice chat (dashboard → Game Configs → Voice Chat), BannerHub will prompt for the **Microphone** permission. You must allow it for in-game calls to work — without it the call falls back to opening in your browser.\n\n> [!IMPORTANT]\n> **Notification permission req"
        ),
)

    /** 拉取远程官方清单（加速重写 + 失败回退内置） */
    suspend fun fetchRemoteRegistry(): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val acceleratedUrl = Accelerator.rewriteUrl(DEFAULT_EMULATOR_REGISTRY_URL)
        val request = Request.Builder().url(acceleratedUrl).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                val entries = parseEmulators(json)
                if (entries.isEmpty()) null else entries
            }
        }.getOrNull() ?: builtInRegistry()
    }

    /** 拉取远程社区排行（下载量/更新日志），失败回退内置 */
    suspend fun fetchRemoteRanking(): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val acceleratedUrl = Accelerator.rewriteUrl(DEFAULT_EMULATOR_RANKING_URL)
        val request = Request.Builder().url(acceleratedUrl).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                val entries = parseEmulators(json)
                if (entries.isEmpty()) null else entries
            }
        }.getOrNull() ?: builtInRegistry()
    }

    private fun parseEmulators(json: JSONObject): List<EmulatorEntry> {
        val arr: JSONArray = json.optJSONArray("emulators") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    EmulatorEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        category = obj.optString("category"),
                        description = obj.optString("description"),
                        owner = obj.optString("owner"),
                        repo = obj.optString("repo"),
                        homepage = obj.optString("homepage"),
                        latestTag = obj.optString("latest_tag"),
                        latestApkUrl = obj.optString("latest_apk_url"),
                        pushedAt = obj.optString("pushed_at"),
                        stars = obj.optLong("stars", 0L),
                        downloads = obj.optLong("downloads", 0L),
                        latestDate = obj.optString("latest_date"),
                        latestBody = obj.optString("latest_body")
                    )
                )
            }
        }.filter { it.id.isNotBlank() }
    }
}
