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
    val repoUrl: String = "",
    val official: Boolean = false,
    val channel: String = "stable",
    val latestTag: String = "",
    val latestApkUrl: String = "",
    val latestApkName: String = "",
    val latestApkSize: Long = 0L,
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
            id = "brunodev85-winlator", name = "Winlator BrunoDev", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "brunodev85",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/brunodev85/winlator",
            official = true, channel = "stable",
            downloads = 16076175, stars = 19417, pushedAt = "2026-09-22T18:36:59Z",
            latestTag = "v11.2.0", latestDate = "2026-08-19T11:06",
            latestApkUrl = "https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk", latestApkName = "Winlator_11.2.apk",
            latestApkSize = 157549075, latestBody = "**v11.2 (Latest)**\n\n- Added an option to save memory on run games from Steam\n- Improved the presentation of rendered images\n- Implemented XInput extension for raw input\n- Implemented an option to run Winlator as foreground service (by @Juan-Antonio-Doe)\n- Updated Mesa Turnip (v26.2.0 from @whitebelyash branch)\n\n**v11.2 (Beta)**\n- Fixed gamepad input lag\n- Added Box64 v0.4.4 (release)\n- Improved Vortek:\n	└─ Improved texture decoding (it was not handling mipmaps correctly)\n	└─ Some im"
        ),
        EmulatorEntry(
            id = "gamehub", name = "GameHub", category = "Winlator",
            description = "",
            owner = "",
            repo = "",
            homepage = "https://gamehub.com", repoUrl = "",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "eden", name = "Eden", category = "Winlator",
            description = "",
            owner = "",
            repo = "",
            homepage = "", repoUrl = "",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "termux", name = "Termux", category = "PC Emulator",
            description = "",
            owner = "termux",
            repo = "termux-app",
            homepage = "https://termux.dev", repoUrl = "https://github.com/termux/termux-app",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "ppsspp", name = "PPSSPP", category = "PC Emulator",
            description = "",
            owner = "hrydgard",
            repo = "ppsspp",
            homepage = "https://www.ppsspp.org", repoUrl = "https://github.com/hrydgard/ppsspp",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "retroarch", name = "RetroArch", category = "PC Emulator",
            description = "",
            owner = "libretro",
            repo = "RetroArch",
            homepage = "https://www.retroarch.com", repoUrl = "https://github.com/libretro/RetroArch",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "dolphin", name = "Dolphin Emulator", category = "PC Emulator",
            description = "",
            owner = "dolphin-emu",
            repo = "dolphin",
            homepage = "https://dolphin-emu.org", repoUrl = "https://github.com/dolphin-emu/dolphin",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "duckstation", name = "DuckStation", category = "PC Emulator",
            description = "",
            owner = "stenzek",
            repo = "duckstation",
            homepage = "https://www.duckstation.org", repoUrl = "https://github.com/stenzek/duckstation",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "vita3k", name = "Vita3K", category = "PC Emulator",
            description = "",
            owner = "Vita3K",
            repo = "Vita3K",
            homepage = "https://vita3k.org", repoUrl = "https://github.com/Vita3K/Vita3K",
            official = true, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "weihuoya-citra", name = "Citra (weihuoya)", category = "Nintendo 3DS",
            description = "A Nintendo 3DS Emulator",
            owner = "weihuoya",
            repo = "citra",
            homepage = "", repoUrl = "https://github.com/weihuoya/citra",
            official = false, channel = "nightly",
            downloads = 9835742, stars = 2762, pushedAt = "2025-11-14T11:04:59Z",
            latestTag = "20251112", latestDate = "2025-11-12T02:51",
            latestApkUrl = "https://github.com/weihuoya/citra/releases/download/20251112/Citra_MMJ_20251112.apk", latestApkName = "Citra_MMJ_20251112.apk",
            latestApkSize = 12166311, latestBody = "**Before:**\nemulator directory: \"citra-emu\"\n\n**Antutu**:\npackage name: com.antutu.ABenchMark\nThis is tricking the os thinks it's running a benchmark app, and will boost performance on some device.\n\n**Storage Access:**\nemulator directory: \"Android/data/org.citra.emu/files/citra-emu\"\nWARNING uninstall citra will remove directory, include games, dlcs, save datas.\n\ndirectory: \"citra-emu\\nand\\data\\00000000000000000000000000000000\\sysdata\\00010026\\00000000\\CEC\"\nDelete this dir"
        ),
        EmulatorEntry(
            id = "azahar-emu-azahar", name = "Azahar", category = "Nintendo 3DS",
            description = "An open-source 3DS emulator project based on Citra.",
            owner = "azahar-emu",
            repo = "azahar",
            homepage = "", repoUrl = "https://github.com/azahar-emu/azahar",
            official = false, channel = "stable",
            downloads = 8614676, stars = 8324, pushedAt = "2026-10-10T13:45:51Z",
            latestTag = "2127.0-alpha1", latestDate = "2026-10-10T16:09",
            latestApkUrl = "https://github.com/azahar-emu/azahar/releases/download/2127.0-alpha1/azahar-android-googleplay-2127.0-alpha1.aab", latestApkName = "azahar-android-googleplay-2127.0-alpha1.aab",
            latestApkSize = 81268438, latestBody = "<h3>⚠️Important Alpha Information⚠️</h3>\n\nAs an alpha release, this build is not yet considered stable.\n\nThis prerelease is intended for users to test upcoming features, allowing the community to provide feedback before they are distributed in a stable release.\nUsers interested in testing bleeding-edge software are encouraged to test out this build to help us catch any issues we may have missed during development.\n\nIf you are unwilling to tolerate potential issues, it may be better to wai"
        ),
        EmulatorEntry(
            id = "Vita3K-Vita3K-Android", name = "Vita3K Android", category = "PSVITA",
            description = "Public releases and CI for the Vita3K Android app",
            owner = "Vita3K",
            repo = "Vita3K-Android",
            homepage = "", repoUrl = "https://github.com/Vita3K/Vita3K-Android",
            official = false, channel = "stable",
            downloads = 4327315, stars = 1666, pushedAt = "2025-02-22T16:30:58Z",
            latestTag = "v12", latestDate = "2024-06-06T20:36",
            latestApkUrl = "https://github.com/Vita3K/Vita3K-Android/releases/download/v12/vita3k-android-release-12.apk", latestApkName = "vita3k-android-release-12.apk",
            latestApkSize = 14823479, latestBody = "Vita3K Android release 12 Changelog (non exhaustive list):\n- SD card support: Allow any folder to be specified as the OS/game data folder (inspired by the PR from nurunuruslime)\n- Allow the overlay to be used at the same time as the touchscreen\n- Fix black bands appearing sometimes at the top and bottom of the application on some devices\n- Fix bug causing game installation to be 10x slower than expected\n- Fix accelerometer and gyroscope on android game consoles\n- Some crash fixes\n- Add co"
        ),
        EmulatorEntry(
            id = "Trixarian-NetherSX2-patch", name = "NetherSX2 Patch", category = "Emulator PS2",
            description = "Continuation of NetherSX2 based on AetherSX2 4248",
            owner = "Trixarian",
            repo = "NetherSX2-patch",
            homepage = "", repoUrl = "https://github.com/Trixarian/NetherSX2-patch",
            official = false, channel = "stable",
            downloads = 3502151, stars = 2679, pushedAt = "2026-09-22T04:21:34Z",
            latestTag = "2.2n", latestDate = "2025-12-25T12:22",
            latestApkUrl = "https://github.com/Trixarian/NetherSX2-patch/releases/download/2.2n/NetherSX2-v2.2n-4248.apk", latestApkName = "NetherSX2-v2.2n-4248.apk",
            latestApkSize = 21621788, latestBody = "Merry Christmas, Happy Holidays and a Happy New Year everyone!\n\nThis update adds experimental Online Multiplayer to NetherSX2 along with the usual updates!\nThanks to [IamLupo](https://github.com/IamLupo) for adding it to ARMSX2, and [zakilj3](https://www.youtube.com/user/zakilj3) for discovering the same settings work with NetherSX2!\n\n[VirusTotal Scan Report](https://www.virustotal.com/gui/file/2029e30dcc1ba1ecb8d73313f18b429b40e6044c7785c52af08b948ef94b3ddf)\n\n## How to use Online Multipl"
        ),
        EmulatorEntry(
            id = "SSimco-Cemu", name = "Cemu", category = "Wii U Emulator",
            description = "Android port for the cemu emulator",
            owner = "SSimco",
            repo = "Cemu",
            homepage = "", repoUrl = "https://github.com/SSimco/Cemu",
            official = false, channel = "stable",
            downloads = 2874222, stars = 1205, pushedAt = "2026-09-12T17:30:42Z",
            latestTag = "0.5", latestDate = "2026-04-25T21:55",
            latestApkUrl = "https://github.com/SSimco/Cemu/releases/download/0.5/Cemu-0.5.apk", latestApkName = "Cemu-0.5.apk",
            latestApkSize = 33124708, latestBody = "Experimental release 0.5\n\n- Implemented options to enable emulated USB devices & configure them"
        ),
        EmulatorEntry(
            id = "StevenMXZ-Winlator-Ludashi", name = "Winlator Ludashi", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "StevenMXZ",
            repo = "Winlator-Ludashi",
            homepage = "", repoUrl = "https://github.com/StevenMXZ/Winlator-Ludashi",
            official = false, channel = "stable",
            downloads = 1529547, stars = 1225, pushedAt = "2026-10-07T21:29:08Z",
            latestTag = "v4.1", latestDate = "2026-09-13T13:26",
            latestApkUrl = "https://github.com/StevenMXZ/Winlator-Ludashi/releases/download/v4.1/ludashi-build.apk", latestApkName = "ludashi-build.apk",
            latestApkSize = 551444161, latestBody = "What's new:\n\n\\- Fixed some games showing a black screen due to incorrect surface format. (like RDR2)\n\\- Updated DisplayX.\n\\- Now works in container mode.\n\\- Added two new options: submit every buffer and precise presentation.\n\\- added D7VK\n\\- Fixed X11 bypass not working on DX12.\n\\- improved Freedreno compatibility \n\\- Various bug fixes, improved stability, and some UI changes based on feedback.\n\n📌 update requires reinstallation"
        ),
        EmulatorEntry(
            id = "Trixarian-NetherSX2-classic", name = "NetherSX2 Classic", category = "Emulator PS2",
            description = "Continuation of NetherSX2 based on AetherSX2 3668",
            owner = "Trixarian",
            repo = "NetherSX2-classic",
            homepage = "", repoUrl = "https://github.com/Trixarian/NetherSX2-classic",
            official = false, channel = "stable",
            downloads = 1507242, stars = 1200, pushedAt = "2026-06-29T12:01:14Z",
            latestTag = "2.2n", latestDate = "2025-12-25T12:22",
            latestApkUrl = "https://github.com/Trixarian/NetherSX2-classic/releases/download/2.2n/NetherSX2-v2.2n-3668.apk", latestApkName = "NetherSX2-v2.2n-3668.apk",
            latestApkSize = 19544036, latestBody = "Merry Christmas, Happy Holidays and a Happy New Year everyone!\n\nThis update adds experimental Online Multiplayer to NetherSX2 along with the usual updates!\nThanks to [IamLupo](https://github.com/IamLupo) for adding it to ARMSX2, and [zakilj3](https://www.youtube.com/user/zakilj3) for discovering the same settings work with NetherSX2!\n\n[VirusTotal Scan Report](https://www.virustotal.com/gui/file/8b502b31ce797d5a162bc6b9300b5f02351b0b6b290b8dc1390b8dfb826673ab)\n\n## How to use Online Multipl"
        ),
        EmulatorEntry(
            id = "Producdevity-gamehub-lite", name = "GameHub Lite (Producdevity)", category = "GameHub",
            description = "GameHub Lite is a community-maintained modified version of GameHub for education purposes",
            owner = "Producdevity",
            repo = "gamehub-lite",
            homepage = "", repoUrl = "https://github.com/Producdevity/gamehub-lite",
            official = false, channel = "stable",
            downloads = 1479161, stars = 2041, pushedAt = "2026-07-03T18:28:52Z",
            latestTag = "v5.1.8", latestDate = "2026-07-02T19:43",
            latestApkUrl = "https://github.com/Producdevity/gamehub-lite/releases/download/v5.1.8/GameHub-Lite-v5.1.8-alt-antutu.apk", latestApkName = "GameHub-Lite-v5.1.8-alt-antutu.apk",
            latestApkSize = 51993473, latestBody = "## Security: Steam token redaction in logs\n\nGameHub Lite 5.1.8 fixes a logging issue where Steam authentication-related values could appear in diagnostic logs. \nIn some cases, logs could include fields such as `steamToken`, `refreshToken`, or `accessToken` from Steam login/launch flows.\n\n**Publicly posted logs containing these fields should be deleted or redacted.**\n\n### IMPORTANT\n\nThis should go without saying but If you find any security issues, please reach out **PRIVATELY**. \nI hav"
        ),
        EmulatorEntry(
            id = "The412Banner-Bannerlator", name = "Bannerlator", category = "Winlator",
            description = "Bannerlator — run Windows PC games and apps on Android (Wine + Box64/86). A personal, community-driven continuation of the discontinued Winlator Star Bionic project. GitHub Actions builds + releases.",
            owner = "The412Banner",
            repo = "Bannerlator",
            homepage = "", repoUrl = "https://github.com/The412Banner/Bannerlator",
            official = false, channel = "stable",
            downloads = 1315528, stars = 405, pushedAt = "2026-10-12T00:50:21Z",
            latestTag = "3.1.6", latestDate = "2026-10-11T06:35",
            latestApkUrl = "https://github.com/The412Banner/Bannerlator/releases/download/3.1.6/Bannerlator-3.1.6-antutu.apk", latestApkName = "Bannerlator-3.1.6-antutu.apk",
            latestApkSize = 551082295, latestBody = "<p align=\"center\">\n<img src=\"https://github.com/The412Banner/Bannerlator/blob/main/logo.jpg?raw=true\" width=\"1500\" height=\"500\" alt=\"Bannerlator\" />\n</p>\n\n<p align=\"center\">\n<img src=\"https://img.shields.io/badge/release-3.1.6-ff3d8b?style=for-the-badge\" alt=\"3.1.6\" />\n&nbsp;<img src=\"https://img.shields.io/badge/update-app--side%20%C2%B7%20no%20ImageFS%20reinstall-2dd4bf?style=for-the-badge\" alt=\"app-side update\" />\n&nbsp;<img src=\"https://img.shields.io/badge/Steam-"
        ),
        EmulatorEntry(
            id = "utkarshdalal-GameNative", name = "GameNative", category = "GameNative",
            description = "Native PC gaming with Steam, Epic, GOG and Amazon integrations on Android",
            owner = "utkarshdalal",
            repo = "GameNative",
            homepage = "", repoUrl = "https://github.com/utkarshdalal/GameNative",
            official = false, channel = "stable",
            downloads = 1314445, stars = 11163, pushedAt = "2026-10-10T18:19:19Z",
            latestTag = "v1.3.0-prerelease", latestDate = "2026-10-04T15:49",
            latestApkUrl = "https://github.com/utkarshdalal/GameNative/releases/download/v1.3.0-prerelease/gamenative-v1.3.0-prerelease-legacy-xr.apk", latestApkName = "gamenative-v1.3.0-prerelease-legacy-xr.apk",
            latestApkSize = 573779844, latestBody = "## Downloads\n- **`gamenative-v1.3.0-prerelease.apk`** — Standard build. Recommended for most users.\n- **`gamenative-v1.3.0-prerelease-legacy-xr.apk`** — XR build for Quest headsets.\n\n\n## What's Changed\n* Added new Steam client in headless mode, fixing online multiplayer in several games (eg AoE II, CS), input in various games (eg No Man's Sky) and fixes DRM for games that were not working in https://github.com/utkarshdalal/GameNative/pull/1937\n* Added EA games support (tested with NFS: MW"
        ),
        EmulatorEntry(
            id = "ARMSX2-ARMSX2", name = "ARMSX2", category = "Emulator PS2",
            description = "ARMSX2 - The Playstation 2 Emulator for ARM64 Platforms",
            owner = "ARMSX2",
            repo = "ARMSX2",
            homepage = "", repoUrl = "https://github.com/ARMSX2/ARMSX2",
            official = false, channel = "stable",
            downloads = 1232724, stars = 2159, pushedAt = "2026-10-11T21:12:33Z",
            latestTag = "2.8.3", latestDate = "2026-10-11T17:16",
            latestApkUrl = "https://github.com/ARMSX2/ARMSX2/releases/download/2.8.3/ARMSX2-2.8.3-a11-armv8.2-sdk30.apk", latestApkName = "ARMSX2-2.8.3-a11-armv8.2-sdk30.apk",
            latestApkSize = 68849731, latestBody = "# ARMSX2 2.8.3\n\nWhat's new: \n\n## MaliSX2 for Mali GPUs\n\nMaliSX2 is a Vulkan driver for Mali GPUs that @bmdhacks built for ARMSX2. In 2.8.2 it was offered only on the Mali-G615 and G715, under its old name, libmali. Now it also covers the Mali-G57, G68, G77 and G78 (including the G78AE), which a lot of handhelds use, the Anbernic RG 476H among them.\n\n<img width=\"2340\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/884734d3-d2b8-4651-a87c-1149d34c2c9b\" />"
        ),
        EmulatorEntry(
            id = "longjunyu2-winlator", name = "Winlator Glibc", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "longjunyu2",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/longjunyu2/winlator",
            official = false, channel = "stable",
            downloads = 1023407, stars = 551, pushedAt = "2026-01-05T23:43:15Z",
            latestTag = "v7.1.5", latestDate = "2025-01-25T09:40",
            latestApkUrl = "https://github.com/longjunyu2/winlator/releases/download/v7.1.5/app-release.apk", latestApkName = "app-release.apk",
            latestApkSize = 244026226, latestBody = "## Warning! This APK is signed by a different key. This means you will have to uninstall the 7.1.3 version first (which will delete all your data!)\n\n## What's New\n#### Added\n- Alsa sound driver updated #183\n- Multikey touch button binding #197\n- Touch haptic when virtual keyboard or controller is enabled #62\n  \n#### Fixed\n- Invisible mouse cursor in some games #192\n- Some 32 bit games not working properly #173\n  \n####  What's new about OpenXR Support?\n- Fullscreen is forced only in "
        ),
        EmulatorEntry(
            id = "flyinghead-flycast", name = "Flycast", category = "Sega Dreamcast",
            description = "Flycast is a multiplatform Sega Dreamcast, Naomi, Naomi 2 and Atomiswave emulator",
            owner = "flyinghead",
            repo = "flycast",
            homepage = "", repoUrl = "https://github.com/flyinghead/flycast",
            official = false, channel = "stable",
            downloads = 880773, stars = 2587, pushedAt = "2026-10-10T01:44:52Z",
            latestTag = "v2.7", latestDate = "2026-08-19T11:47",
            latestApkUrl = "https://github.com/flyinghead/flycast/releases/download/v2.7/flycast-2.7.apk", latestApkName = "flycast-2.7.apk",
            latestApkSize = 36280670, latestBody = "## New features\n* Frame rate detection and pacing\n* Localization (fr, hu, jp, pt_BR, sv)\n* Multiscreen support for Airline Pilot, Sega Strike Fighter and Derby Owners Club\n* Improved modem emulation\n* improved DreamPicoPort and DreamPotato support by @RikkiGibson, @Tails86 and @nexus382\n* Android: Google Auto Backup support for VMU saves and non-volatile memory\n\n## Fixed Games\n* Airline Pilot (Naomi, multiscreen)\n* Cyber Troopers Virtual-On: Oratorio Tangram (JP, online features)\n* De"
        ),
        EmulatorEntry(
            id = "RPCSX-rpcsx-ui-android", name = "RPCSX Android", category = "Emulator PS3",
            description = "",
            owner = "RPCSX",
            repo = "rpcsx-ui-android",
            homepage = "", repoUrl = "https://github.com/RPCSX/rpcsx-ui-android",
            official = false, channel = "stable",
            downloads = 851292, stars = 1330, pushedAt = "2026-08-29T13:27:19Z",
            latestTag = "v20250425", latestDate = "2025-04-25T16:02",
            latestApkUrl = "https://github.com/RPCSX/rpcsx-ui-android/releases/download/v20250425/rpcsx-release.apk", latestApkName = "rpcsx-release.apk",
            latestApkSize = 11134143, latestBody = "**Full Changelog**: https://github.com/RPCSX/rpcsx-ui-android/compare/v20250417...v20250425"
        ),
        EmulatorEntry(
            id = "coffincolors-winlator", name = "Winlator Coffincolors", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "coffincolors",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/coffincolors/winlator",
            official = false, channel = "stable",
            downloads = 844582, stars = 1115, pushedAt = "2025-12-09T22:36:49Z",
            latestTag = "cmod_v13.1", latestDate = "2025-08-07T07:49",
            latestApkUrl = "https://github.com/coffincolors/winlator/releases/download/cmod_v13.1/cmod-hotfix-checklist-08-07-2025.png", latestApkName = "cmod-hotfix-checklist-08-07-2025.png",
            latestApkSize = 161893, latestBody = "_Minor update v13.1.1 fixed custom icons, default renderer, and warning prompt handling on new installs_ \n\n------------------------------\n\n-   **`evshim` Rewrite:** The entire controller shim (`libevshim.so`) has been rewritten, **completely eliminating the use of shared memory (`mmap`)**, resulting in a massive reduction in CPU overhead and a significant performance boost, especially on lower-end devices. Box64 3.2 and 3.6 are now compatible, and have been restored.\n\n-   **ALSA-Reflector "
        ),
        EmulatorEntry(
            id = "afeimod-winlator-mod", name = "Winlator Afei", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "afeimod",
            repo = "winlator-mod",
            homepage = "", repoUrl = "https://github.com/afeimod/winlator-mod",
            official = false, channel = "stable",
            downloads = 820520, stars = 698, pushedAt = "2026-04-19T02:53:06Z",
            latestTag = "10.1hotfix-Amod3fix2", latestDate = "2025-12-02T08:21",
            latestApkUrl = "https://github.com/afeimod/winlator-mod/releases/download/10.1hotfix-Amod3fix2/Winlator10.1-Amod3fix2.apk", latestApkName = "Winlator10.1-Amod3fix2.apk",
            latestApkSize = 879369555, latestBody = "1-修复部分游戏mod失效，比如死或生5，上古卷轴五等\n2-提供wine-10.15.whp可以在额外内容里安装，这个wine可以播放一些视频白屏有声音的游戏\n3-其他更新见fix1"
        ),
        EmulatorEntry(
            id = "Medard22-Dolphin-MMJR2-VBI", name = "Dolphin MMJR2 VBI", category = "Nintendo GameCube / Wii",
            description = "A fork of Dolphin MMJR2 - with added VBI Skip hack and further fixes ported over from Dolphin Official.",
            owner = "Medard22",
            repo = "Dolphin-MMJR2-VBI",
            homepage = "", repoUrl = "https://github.com/Medard22/Dolphin-MMJR2-VBI",
            official = false, channel = "stable",
            downloads = 773887, stars = 278, pushedAt = "2025-08-03T09:27:46Z",
            latestTag = "U24.07.02-2407", latestDate = "2025-07-20T08:56",
            latestApkUrl = "https://github.com/Medard22/Dolphin-MMJR2-VBI/releases/download/U24.07.02-2407/Dolphin.MMJR2-VBI-U24.07.02-2407.apk", latestApkName = "Dolphin.MMJR2-VBI-U24.07.02-2407.apk",
            latestApkSize = 13033337, latestBody = "Notable changes from U24.03.22-21259\n\n- Code refactoring and minor fixes\n- Fixed a panic alert for Def Jam: Fight for New York and Fifa Street\n- Numerous small optimizations to JitARM64\n- Small optimizations to DSP HLE audio decoding\n- Fixed stuttering from JIT Cache clear - mostly notable in Shadow the Hedgehog\n- Set OSD message color to yellow in default AMOLED theme\n- Updated Vulkan Memory Allocator and Vulkan Headers\n- Updated and optimized app icons\n\nMissing features - these feat"
        ),
        EmulatorEntry(
            id = "xodiosx-XoDos", name = "XoDos", category = "PC Emulator",
            description = "",
            owner = "xodiosx",
            repo = "XoDos",
            homepage = "", repoUrl = "https://github.com/xodiosx/XoDos",
            official = false, channel = "stable",
            downloads = 755719, stars = 740, pushedAt = "2026-05-04T03:29:50Z",
            latestTag = "v6.0.0+c050ebd", latestDate = "2025-11-09T15:21",
            latestApkUrl = "https://github.com/xodiosx/XoDos/releases/download/v6.0.0%2Bc050ebd/XoDos_apt-android-7-debug_arm64-v8a.apk", latestApkName = "XoDos_apt-android-7-debug_arm64-v8a.apk",
            latestApkSize = 54133653, latestBody = "**XoDos-6.0.0.r4 APKs**\n💝 Install over the old APK to UPDATE \n\n🎆Happy holidays 🎊\nThis going to be the last update for the development would be paused until further notice thanks for everyone support this project was a blessing, because All those who Loved and support it till today,, and maybe if google Changed it's 2026 policy against developers we can see more and more awesome projects from GitHub and other development places to improve android community and make it great again,,,♥️\n \n✅Fi"
        ),
        EmulatorEntry(
            id = "aenu1-aps3e", name = "APS3e", category = "Emulator PS3",
            description = "An experimental Android PS3 emulator, ported and optimized based on RPCS3.",
            owner = "aenu1",
            repo = "aps3e",
            homepage = "", repoUrl = "https://github.com/aenu1/aps3e",
            official = false, channel = "stable",
            downloads = 735432, stars = 738, pushedAt = "2026-10-05T09:08:05Z",
            latestTag = "2.43", latestDate = "2026-10-05T09:08",
            latestApkUrl = "https://github.com/aenu1/aps3e/releases/download/2.43/2.43.apk", latestApkName = "2.43.apk",
            latestApkSize = 40122859, latestBody = ""
        ),
        EmulatorEntry(
            id = "Fcharan-WinlatorMali", name = "Winlator Mali", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "Fcharan",
            repo = "WinlatorMali",
            homepage = "", repoUrl = "https://github.com/Fcharan/WinlatorMali",
            official = false, channel = "stable",
            downloads = 640512, stars = 440, pushedAt = "2026-10-04T12:44:25Z",
            latestTag = "bionic-mali-1.1", latestDate = "2026-07-09T19:05",
            latestApkUrl = "https://github.com/GunaCharanTeja/WinlatorMali/releases/download/bionic-mali-1.1/Winlator-Mali-1.1-Antutu.apk", latestApkName = "Winlator-Mali-1.1-Antutu.apk",
            latestApkSize = 789162895, latestBody = "Winlator Mali Bionic v1.1\n\n🚀 New Features\n\n- Added Fake HDR.\n- Added a Universal FPS Limiter.\n- Added a Sharpness option.\n- Added GTA V Optimization toggle in Shortcut Settings.\n- Added DXVK Config toggle with built-in pre-made configuration presets.\n- Automatically generates a dxvk.conf file for each container based on the selected preset.\n- Added new DXVK versions.\n- Added the new GameNative Vulkan Wrapper.\n- Added new Box64 and FEX presets.\n\n---\n\n🎮 Unreal Engine 3 Preset\n\n- "
        ),
        EmulatorEntry(
            id = "Ashnar2602-X360-Mobile---OFFICIAL", name = "X360 Mobile", category = "Xbox",
            description = "",
            owner = "Ashnar2602",
            repo = "X360-Mobile---OFFICIAL",
            homepage = "", repoUrl = "https://github.com/Ashnar2602/X360-Mobile---OFFICIAL",
            official = false, channel = "nightly",
            downloads = 524382, stars = 0, pushedAt = "",
            latestTag = "v0.6.3", latestDate = "2026-08-17T19:30",
            latestApkUrl = "https://github.com/Ashnar2602/X360-Mobile---OFFICIAL/releases/download/v0.6.3/x360-companion-0.1.0.apk", latestApkName = "x360-companion-0.1.0.apk",
            latestApkSize = 9486485, latestBody = "# X360 Mobile 0.6.3 and X360 Companion 0.1.0\n\nX360 Mobile 0.6.3 introduces local wireless multiplayer through the new X360\nCompanion application, alongside Android Home shortcuts, a clearer driver\ncatalog and a unified touch-control system.\n\n## X360 Companion 0.1.0\n\n- Added X360 Companion, a separate Android application that turns a phone on\n  the same local network or a paired Bluetooth device into a remote XInput\n  controller.\n- Up to three companions can be assigned independently t"
        ),
        EmulatorEntry(
            id = "ARMSX2-ARMSX3", name = "ARMSX3", category = "Emulator PS3",
            description = "",
            owner = "ARMSX2",
            repo = "ARMSX3",
            homepage = "", repoUrl = "https://github.com/ARMSX2/ARMSX3",
            official = false, channel = "stable",
            downloads = 499909, stars = 0, pushedAt = "",
            latestTag = "1.1.1", latestDate = "2026-10-11T15:57",
            latestApkUrl = "https://github.com/ARMSX2/ARMSX3/releases/download/1.1.1/ARMSX3-1.1.1-a11-armv8.2-sdk30.apk", latestApkName = "ARMSX3-1.1.1-a11-armv8.2-sdk30.apk",
            latestApkSize = 85805120, latestBody = "# ARMSX3 1.1.1\n\n<img width=\"1920\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/2b106a7e-eaf8-4c29-bf94-1a329ae1af03\" />\n\n## Battery Life\n\n- **Much less battery drain with a game paused or when the app is in the background/device in sleep mode with the app open.** Locking the device with a game open kept the emulator checking in about 2,000 times a second, and the library background kept animating out of sight. Now the emulator only checks in a few times"
        ),
        EmulatorEntry(
            id = "HorizonEmuTeam-Horizon-Emu", name = "Horizon Emu", category = "PC Emulator",
            description = "",
            owner = "HorizonEmuTeam",
            repo = "Horizon-Emu",
            homepage = "", repoUrl = "https://github.com/HorizonEmuTeam/Horizon-Emu",
            official = false, channel = "stable",
            downloads = 486786, stars = 717, pushedAt = "2025-02-28T07:48:56Z",
            latestTag = "v4", latestDate = "2025-02-28T07:48",
            latestApkUrl = "https://github.com/HorizonEmuTeam/Horizon-Emu/releases/download/v4/HorizonEmu4.NotConnectedFix.apk", latestApkName = "HorizonEmu4.NotConnectedFix.apk",
            latestApkSize = 36803150, latestBody = "After minor changes like Box64, Wine (all have almost the same performance), DXVK and Turnip and me not having time to do anything with this project, it's finally getting a \"Not Connected\" fix related to the usage of /tmp directory on some devices by the termux-x11 library in the app. Should work on Android 15 with this fix as well, people already reported it working"
        ),
        EmulatorEntry(
            id = "The412Banner-bannerhub", name = "BannerHub (The412Banner)", category = "GameHub",
            description = "GameHub ReVanced — extended with GOG, Epic & Amazon game libraries, Component Manager, Winlator HUD, per-game controls & configs, and more. Built via apktool smali patching.",
            owner = "The412Banner",
            repo = "bannerhub",
            homepage = "", repoUrl = "https://github.com/The412Banner/bannerhub",
            official = false, channel = "stable",
            downloads = 363461, stars = 529, pushedAt = "2026-09-28T21:24:36Z",
            latestTag = "v3.8.1", latestDate = "2026-09-12T01:44",
            latestApkUrl = "https://github.com/The412Banner/BannerHub/releases/download/v3.8.1/BannerHub-v3.8.1-alt-AnTuTu.apk", latestApkName = "BannerHub-v3.8.1-alt-AnTuTu.apk",
            latestApkSize = 145626131, latestBody = "> [!WARNING]\n> **Only install official stable releases** (like this one). Do NOT install pre-releases or raw CI artifacts — pre-release builds use a different package name (`com.tencent.ig`) and cannot be upgraded to stable without uninstalling first.\n\n> [!IMPORTANT]\n> **Microphone permission required for Voice Chat.** When you activate voice chat (dashboard → Game Configs → Voice Chat), BannerHub will prompt for the **Microphone** permission. You must allow it for in-game calls to work — wi"
        ),
        EmulatorEntry(
            id = "winebox64-winlator", name = "Wb64dev", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "winebox64",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/winebox64/winlator",
            official = false, channel = "stable",
            downloads = 296810, stars = 535, pushedAt = "2025-03-18T01:28:27Z",
            latestTag = "v10.2", latestDate = "2025-03-03T03:01",
            latestApkUrl = "https://github.com/winebox64/winlator/releases/download/v10.2/winlator.apk", latestApkName = "winlator.apk",
            latestApkSize = 137000935, latestBody = "[![logo](https://github.com/user-attachments/assets/bd81f1a1-2391-4c39-abbd-a42b1f5ef65f)](https://github.com/winebox64/winlator/releases)\n\n\n<p align=\"left\">\n<img  src=\"https://img.shields.io/badge/Winlator%2010.2-white?style=plastic&logo=snapdragon&logoColor=C00000\" width=\"140\" /> \n</p>\n\n# Released for personal use\n\n### Winlator 10.2 (beta)\n- Now smaller, lighter, and faster! Save storage with optimized app packaged in 130MB and installed in just 1.3GB.\n- Updated latest DXVK As"
        ),
        EmulatorEntry(
            id = "rfandango-XenDroid", name = "XenDroid", category = "Xbox",
            description = "",
            owner = "rfandango",
            repo = "XenDroid",
            homepage = "", repoUrl = "https://github.com/rfandango/XenDroid",
            official = false, channel = "stable",
            downloads = 201593, stars = 0, pushedAt = "",
            latestTag = "XenDroid-c4f6863", latestDate = "2026-08-14T11:11",
            latestApkUrl = "https://github.com/rfandango/XenDroid/releases/download/XenDroid-c4f6863/XenDroid_Release_c4f6863.apk", latestApkName = "XenDroid_Release_c4f6863.apk",
            latestApkSize = 13655333, latestBody = "## What's Changed\n* [Android] Fix 5ef8fc6 regressions, pause UI improvements, overlay controller toggle by @rfandango in https://github.com/rfandango/XenDroid/pull/79\n\n\n**Full Changelog**: https://github.com/rfandango/XenDroid/compare/XenDroid-b70d643...XenDroid-c4f6863"
        ),
        EmulatorEntry(
            id = "XHYN-PH-exagear-302", name = "ExaGear 302", category = "PC Emulator",
            description = "Simple Modification of ExaGear: Windows Emulator (^^)",
            owner = "XHYN-PH",
            repo = "exagear-302",
            homepage = "", repoUrl = "https://github.com/XHYN-PH/exagear-302",
            official = false, channel = "stable",
            downloads = 172545, stars = 330, pushedAt = "2025-02-25T11:41:52Z",
            latestTag = "v3.0.2.2-egw", latestDate = "2024-01-12T16:20",
            latestApkUrl = "https://github.com/XHYN-PH/exagear-302/releases/download/v3.0.2.2-egw/EGW_302_signed.apk", latestApkName = "EGW_302_signed.apk",
            latestApkSize = 274648295, latestBody = "### EGW 3.0.2.2 UPDATE (1/13/24)\n\nTHIS CACHE IS VERY OLD AND ONLY SUITABLE FOR OLD GAMES... GAMES MAY NOT RUN PROPERLY.\n\nAPK CHANGES:\n- Added Inject Document Provider (MT Manager)\n- Removed Languages Option in Container Properties\n- Removed GPU Option in Container Properties\n- Removed All language string values on the APK.\n- Removed 3 Others Option in the Container Properties\n- Changed the default resolution to 800x600\n- Changed the default language to English\n- Changed the default r"
        ),
        EmulatorEntry(
            id = "Stredohori-Winlator-CMOD", name = "Winlator Bionic Stredohiri", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "Stredohori",
            repo = "Winlator-CMOD",
            homepage = "", repoUrl = "https://github.com/Stredohori/Winlator-CMOD",
            official = false, channel = "stable",
            downloads = 145703, stars = 218, pushedAt = "2026-05-12T18:19:19Z",
            latestTag = "e120cf1", latestDate = "2026-02-02T12:47",
            latestApkUrl = "https://github.com/Stredohori/Winlator-CMOD/releases/download/e120cf1/CMOD_3DMark.apk", latestApkName = "CMOD_3DMark.apk",
            latestApkSize = 526043927, latestBody = "Latest changes from the developer.\nFixed graphical response of pressing the button by coffincolors.\nThe controller will _not_ work with this version.\nSet box64-0.4.1-fix, wowbox64-0.4.1-fix, fexcore-2601 and turnip-26.0.0 by StevenMXZ as default.\nAdvanced box64 profile settings.\nUse apk's at your own risk.\n\n_Assets:_\n\n- **CMOD_Genshin:**\n Can activate hidden features like frame interpolation on Realme devices.\n- **CMOD_AnTuTu:**\n Can maximize clocks of all single cores of the CPU par"
        ),
        EmulatorEntry(
            id = "winlatorbrasil-gamehub-brasil", name = "GameHub Brasil", category = "GameHub",
            description = "",
            owner = "winlatorbrasil",
            repo = "gamehub-brasil",
            homepage = "", repoUrl = "https://github.com/winlatorbrasil/gamehub-brasil",
            official = false, channel = "stable",
            downloads = 139491, stars = 0, pushedAt = "",
            latestTag = "GameHub_Brasil_V3", latestDate = "2026-08-10T03:32",
            latestApkUrl = "https://github.com/winlatorbrasil/gamehub-brasil/releases/download/GameHub_Brasil_V3/GameHub-Brasil-V3-alt-AnTuTu.apk", latestApkName = "GameHub-Brasil-V3-alt-AnTuTu.apk",
            latestApkSize = 79369854, latestBody = "<p align=\"center\">\n  <img src=\"https://github.com/user-attachments/assets/607a4014-a7ab-4cb8-b088-ff294f0bf845\" alt=\"GameHub Brasil\" width=\"600\"/>\n</p>\n\n<p align=\"center\">\n  <a href=\"https://github.com/winlatorbrasil/gamehub-brasil/releases/tag/GameHub_Brasil_V3\">\n    <img\n      src=\"https://img.shields.io/github/downloads/winlatorbrasil/gamehub-brasil/GameHub_Brasil_V3/total?style=for-the-badge&logo=github&logoColor=fff&label=GAMEHUB%20BRASIL%20V3%20DOWNLOADS&color=0969da&ca"
        ),
        EmulatorEntry(
            id = "WinNative-Emu-WinNative", name = "WinNative (WinNative-Emu)", category = "Winlator",
            description = "An Android app for playing Windows games from Steam, Epic Games, GOG, and more on your device.",
            owner = "WinNative-Emu",
            repo = "WinNative",
            homepage = "", repoUrl = "https://github.com/WinNative-Emu/WinNative",
            official = false, channel = "stable",
            downloads = 138946, stars = 681, pushedAt = "2026-10-11T17:39:03Z",
            latestTag = "v0.7.1-beta", latestDate = "2026-09-28T14:06",
            latestApkUrl = "https://github.com/WinNative-Emu/WinNative/releases/download/v0.7.1-beta/WinNative-v0.7.1-beta-Antutu-signed.apk", latestApkName = "WinNative-v0.7.1-beta-Antutu-signed.apk",
            latestApkSize = 607432054, latestBody = "## What's Changed\n* DIS: warp-based interpolation with source-point flow lookup by @qwertypower in https://github.com/WinNative-Emu/WinNative/pull/775\n* Linux Steam client: download to the chosen folder and follow moved games by @maxjivi05 in https://github.com/WinNative-Emu/WinNative/pull/777\n* DIS: optimize OF, interpolation calculations  by @qwertypower in https://github.com/WinNative-Emu/WinNative/pull/776\n* Fix/performance and vac by @maxjivi05 in https://github.com/WinNative-Emu/WinNat"
        ),
        EmulatorEntry(
            id = "jacojayy-star", name = "Star (fork)", category = "Winlator",
            description = "WinHub - The brand new, opensource one-stop place for all your games, now on Android.",
            owner = "jacojayy",
            repo = "star",
            homepage = "", repoUrl = "https://github.com/jacojayy/star",
            official = false, channel = "stable",
            downloads = 120741, stars = 436, pushedAt = "2026-10-06T19:44:52Z",
            latestTag = "ignore", latestDate = "2026-09-27T10:58",
            latestApkUrl = "https://github.com/winhub-emu/winhub/releases/download/ignore/1.json", latestApkName = "1.json",
            latestApkSize = 19, latestBody = "ignore this, its just a test for winhub"
        ),
        EmulatorEntry(
            id = "KreitinnSoftware-MiceWine-Application", name = "MiceWine", category = "PC Emulator",
            description = "",
            owner = "KreitinnSoftware",
            repo = "MiceWine-Application",
            homepage = "", repoUrl = "https://github.com/KreitinnSoftware/MiceWine-Application",
            official = false, channel = "stable",
            downloads = 115614, stars = 0, pushedAt = "",
            latestTag = "ba4b91b", latestDate = "2026-04-09T16:39",
            latestApkUrl = "https://github.com/KreitinnSoftware/MiceWine-Application/releases/download/ba4b91b/app-universal-debug.apk", latestApkName = "app-universal-debug.apk",
            latestApkSize = 34279722, latestBody = ""
        ),
        EmulatorEntry(
            id = "oxters168-Pluvia", name = "Pluvia", category = "PC Emulator",
            description = "Lightweight unofficial Steam client for Android",
            owner = "oxters168",
            repo = "Pluvia",
            homepage = "", repoUrl = "https://github.com/oxters168/Pluvia",
            official = false, channel = "stable",
            downloads = 99964, stars = 1724, pushedAt = "2026-02-23T19:23:53Z",
            latestTag = "v1.3.2", latestDate = "2025-03-02T00:58",
            latestApkUrl = "https://github.com/oxters168/Pluvia/releases/download/v1.3.2/universal.apk", latestApkName = "universal.apk",
            latestApkSize = 284413333, latestBody = "Fixes:\n- Notification action \"Exit\" not terminating service when not in focus. - @LossyDragon @oxters168\n- Proper fix for library padding crash from 1.3.1 hotfix. - @LossyDragon\n- Left pane in a 2-Pane layout should fill the width properly (Library and Friends list) - @LossyDragon\n- Hide keyboard when pressing search/enter button. - @LossyDragon\n- Fix family shared games missing from library list @LossyDragon @oxters168\n\nAdditions:\n- New app icon/logo by @Prowi3 Huge THANK YOU for this"
        ),
        EmulatorEntry(
            id = "ajay9634-winlator-ajay", name = "Winlator Ajay", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "ajay9634",
            repo = "winlator-ajay",
            homepage = "", repoUrl = "https://github.com/ajay9634/winlator-ajay",
            official = false, channel = "stable",
            downloads = 97473, stars = 141, pushedAt = "2026-06-28T11:07:57Z",
            latestTag = "v11.1-hotfix2", latestDate = "2026-06-15T05:01",
            latestApkUrl = "https://github.com/ajay9634/winlator-ajay/releases/download/v11.1-hotfix2/Winlator_11.1_mod_Ajay.apk", latestApkName = "Winlator_11.1_mod_Ajay.apk",
            latestApkSize = 169771652, latestBody = "## Changelog (Based on Official v11.1 Hotfix 2)\n\n(Go to Official Winlator GitHub to get latest winlator 11.1 final)\n---\n### **Ajay_Custom_Components**\nYou can install various components—such as d7vk, dxwrapper_ddraw, dxwrapper_d3d8, d3d8to9, and custom—directly from the `D:/winlator/Ajay_Custom_Components` directory. If a newer version of these components becomes available, simply replace the existing DLLs in that folder to update them.\n\nThese components will be installed upon container l"
        ),
        EmulatorEntry(
            id = "Swordfish90-Lemuroid", name = "Lemuroid", category = "All In One",
            description = "All in one emulator on Android!",
            owner = "Swordfish90",
            repo = "Lemuroid",
            homepage = "", repoUrl = "https://github.com/Swordfish90/Lemuroid",
            official = false, channel = "stable",
            downloads = 97070, stars = 4398, pushedAt = "2026-10-05T22:15:29Z",
            latestTag = "1.17.0", latestDate = "2026-04-21T08:52",
            latestApkUrl = "https://github.com/Swordfish90/Lemuroid/releases/download/1.17.0/lemuroid-app-free-dynamic-release.apk", latestApkName = "lemuroid-app-free-dynamic-release.apk",
            latestApkSize = 11985230, latestBody = "* Added microphone support for Nintendo DS\n* Brand new touch controls\n* Add immersive mode (dynamic background color)\n* Slightly improved HD mode\n* Added quicksave/quickload gamepad shortcuts\n* Added support for 16Kb pages\n* Added best-effort saves when transitioning to background\n* Updated all cores and databases\n* Various UI/UX improvements"
        ),
        EmulatorEntry(
            id = "REF4IK-winlator-ref4ik-", name = "Winlator Ref4ik", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "REF4IK",
            repo = "winlator-ref4ik-",
            homepage = "", repoUrl = "https://github.com/REF4IK/winlator-ref4ik-",
            official = false, channel = "stable",
            downloads = 88901, stars = 129, pushedAt = "2026-09-11T15:09:20Z",
            latestTag = "V2", latestDate = "2026-09-11T14:45",
            latestApkUrl = "https://github.com/REF4IK/CronyX-/releases/download/V2/cronyX_v2_antutu.apk", latestApkName = "cronyX_v2_antutu.apk",
            latestApkSize = 625488942, latestBody = "# Release notes — 67956fb..ad04ac3 (27 commits)\n\n**Project renamed!** The project has been renamed from **Winlator ref4ik** to **CronyX** — the app will now appear under the new name. All previous versions were released under the name **Winlator ref4ik**, but from now on the project will be called **CronyX**.\n\n**What's New in This Version:**\n\n- LSFG frame generation moved in-tree (`cpp/winlator/lsfg/` + DXBC): external `lsfg-vk-android` tree removed, `LsfgVkManager` replaced by `LsfgNative"
        ),
        EmulatorEntry(
            id = "JURIS-X-winlator_x", name = "Winlator X", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "JURIS-X",
            repo = "winlator_x",
            homepage = "", repoUrl = "https://github.com/JURIS-X/winlator_x",
            official = false, channel = "stable",
            downloads = 83095, stars = 31, pushedAt = "2025-05-26T11:10:39Z",
            latestTag = "v3.0.1-beta", latestDate = "2025-04-22T14:19",
            latestApkUrl = "https://github.com/JURIS-X/winlator_x/releases/download/v3.0.1-beta/app-debug.apk", latestApkName = "app-debug.apk",
            latestApkSize = 1196028187, latestBody = "Added a new driver to the bionic container.\n8E-800.30\n8E-800.33\nVOLTEK\nv25.2.0_a710_a720_test (compiled by K11MCH1)\nFix some bugs"
        ),
        EmulatorEntry(
            id = "slaker222-Steamlator", name = "Steamlator", category = "Winlator",
            description = "Android application for running Steam games with Proton/Wine and Box86/Box64",
            owner = "slaker222",
            repo = "Steamlator",
            homepage = "", repoUrl = "https://github.com/slaker222/Steamlator",
            official = false, channel = "stable",
            downloads = 68826, stars = 137, pushedAt = "2026-04-29T14:09:07Z",
            latestTag = "1.7", latestDate = "2026-04-29T11:14",
            latestApkUrl = "https://github.com/slaker222/Steamlator/releases/download/1.7/Steamlator.1.7.apk", latestApkName = "Steamlator.1.7.apk",
            latestApkSize = 878890302, latestBody = "Split controls into two tabs:\n\n- Sensor\n- Gamepad\n\n<img width=\"200\" height=\"200\" alt=\"Untitled\" src=\"https://github.com/user-attachments/assets/339584fd-eef1-46e8-a076-84b0e61bdb76\" />\n\nIn the Gamepad tab, visual gamepad button display was added.\n\nAdded the **Controller Manager** button  \n(similar to the Winlator Cmod implementation)\n\nAdded support for custom icons  \n(2 formats available: full white mode / original color mode)\n\n<img width=\"100\" height=\"100\" alt=\"Untit"
        ),
        EmulatorEntry(
            id = "jhinzuo-winlator", name = "Winlator Bionic jhinzuo", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "jhinzuo",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/jhinzuo/winlator",
            official = false, channel = "stable",
            downloads = 63975, stars = 156, pushedAt = "2025-08-24T22:41:56Z",
            latestTag = "dev8", latestDate = "2025-08-24T22:41",
            latestApkUrl = "https://github.com/jhinzuo/winlator/releases/download/dev8/app-debug.apk", latestApkName = "app-debug.apk",
            latestApkSize = 518762406, latestBody = "### Changelog\n\n* `winlator_bionic` -> `upstream` : `winlator_bionic_devbranch`\n* changed wfm to a version without icons for faster loading, thanks to de0ver for this.\n* re-added dxvk scale and opacity\n* updated adrenotools\n* added virtual gamepad lightup.\n* Merge branch 'winlator_bionic' into 'winlator_bionic_dev5'\n* `DXVKConfigDialog`: refractor setEnvVars\n* `WineD3DConfigDialog`: fix typo\n* Add a `WineD3DConfigDialog` to configure wined3d\n* `ContainerDetailFragment`: remove all wine"
        ),
        EmulatorEntry(
            id = "Honkonx-winlator-honkon", name = "Winlator Honkon", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "Honkonx",
            repo = "winlator-honkon",
            homepage = "", repoUrl = "https://github.com/Honkonx/winlator-honkon",
            official = false, channel = "stable",
            downloads = 30439, stars = 28, pushedAt = "2026-03-22T01:01:37Z",
            latestTag = "V11.1", latestDate = "2025-10-19T16:44",
            latestApkUrl = "https://github.com/Honkonx/winlator-honkon/releases/download/V11.1/Winlator.honkon_v11.apk", latestApkName = "Winlator.honkon_v11.apk",
            latestApkSize = 410786290, latestBody = "### VERSION WINLATOR V11 MOD\n\n***Esta versión sigue siendo básica pero trae algunas mejoras en general y otras más específicas para dispositivo mediatek,gpu mali y gpu xclipse.***\n\n### _registro_ _de_ _cambios_\n\n•Todo los cambios de la versión oficial de Bruno.\n•Se agregó el menu de contenido de la versión afeimod(creditos y agradecimiento).\n•Traduje gran parte de la interfaz a español además de cambiar el logo y tema.\n•Se cambió el directorio de donde se descarga el contenido,esto para"
        ),
        EmulatorEntry(
            id = "winlatorbrasil-Winlator-Brasil", name = "Winlator Brasil", category = "Winlator",
            description = "",
            owner = "winlatorbrasil",
            repo = "Winlator-Brasil",
            homepage = "", repoUrl = "https://github.com/winlatorbrasil/Winlator-Brasil",
            official = false, channel = "stable",
            downloads = 24046, stars = 0, pushedAt = "",
            latestTag = "Winlator_Brasil_10.1", latestDate = "2025-05-24T01:26",
            latestApkUrl = "https://github.com/winlatorbrasil/Winlator-Brasil/releases/download/Winlator_Brasil_10.1/wine-9.15-custom.whp", latestApkName = "wine-9.15-custom.whp",
            latestApkSize = 82243500, latestBody = "### 🆕 Winlator_Brasil_10.1, Winlator_Brasil_10.1_AI e Winlator Brasil CLT – Novas Versões\n\nEsta atualização traz duas versões, **Winlator Brasil 10.1**, **Winlator_Brasil_10.1_AI** e **Winlator Brasil CLT**. \n\n**Permitindo instalar as 3 versões ao mesmo tempo, no seu celular**.\n\n![Screenshot_2025-05-28-13-46-30-893_com miui home](https://github.com/user-attachments/assets/11ae82c8-c174-425a-909d-0aa1b31db7f5)\n\n\n---\n\nO **Winlator Brasil 10.1** traz diversas melhorias e recursos adicion"
        ),
        EmulatorEntry(
            id = "jaycore-mobox-patched", name = "Mobox Patched", category = "PC Emulator",
            description = "",
            owner = "jaycore",
            repo = "mobox-patched",
            homepage = "", repoUrl = "https://github.com/jaycore/mobox-patched",
            official = false, channel = "stable",
            downloads = 21265, stars = 77, pushedAt = "2025-06-26T21:09:18Z",
            latestTag = "Mobox_Patched_3.0", latestDate = "2025-06-26T21:09",
            latestApkUrl = "https://github.com/jaycore/mobox-patched/releases/download/Mobox_Patched_3.0/mobox_patched_3.0.tar.gz", latestApkName = "mobox_patched_3.0.tar.gz",
            latestApkSize = 1914631295, latestBody = "June 26,  2025\n\nUpdated glibc to 2.41-1\nAdded box64 0.3.6 & 0.3.7\nAdded wine 10\nAdded dxvk 2.6.1, 2.6.1 gplasync & 2.6.2\nAdded new testd3d and gpu info in app menu"
        ),
        EmulatorEntry(
            id = "ItzDFPlayer-gamehub-lite", name = "GameHub Lite (ItzDFPlayer)", category = "GameHub",
            description = "GameHub Lite is a community-maintained modified version of GameHub for education purposes",
            owner = "ItzDFPlayer",
            repo = "gamehub-lite",
            homepage = "", repoUrl = "https://github.com/ItzDFPlayer/gamehub-lite",
            official = false, channel = "stable",
            downloads = 8279, stars = 48, pushedAt = "2026-01-06T21:01:46Z",
            latestTag = "5.3.5-v2", latestDate = "2026-01-06T21:01",
            latestApkUrl = "https://github.com/ItzDFPlayer/gamehub-lite/releases/download/5.3.5-v2/GameHub_5.3.5_Lite_V2-hotfix.apk", latestApkName = "GameHub_5.3.5_Lite_V2-hotfix.apk",
            latestApkSize = 134908166, latestBody = "Changelog:\n• Ported SD Card support from recent [GameHub 5.3.3 Release Candidate](https://github.com/Producdevity/gamehub-lite/releases/tag/v5.3.3-RC1) releases\n• Merged two APKs into one - added API switch into settings, by default it's set to EmuReady API, can be switched to official GameHub API, restart the app after changing API\n\nHotfix:\n• Included Documents Provider patch (access to /data/data without root)"
        ),
        EmulatorEntry(
            id = "WinlatorXR-WinlatorXR", name = "Winlator XR", category = "Winlator",
            description = "Windows apps/games emulator for standalone OpenXR headsets.",
            owner = "WinlatorXR",
            repo = "WinlatorXR",
            homepage = "", repoUrl = "https://github.com/WinlatorXR/WinlatorXR",
            official = false, channel = "stable",
            downloads = 7146, stars = 193, pushedAt = "2026-10-09T17:41:04Z",
            latestTag = "winlatorxr_dawn42", latestDate = "2026-10-09T17:41",
            latestApkUrl = "https://github.com/WinlatorXR/WinlatorXR/releases/download/winlatorxr_dawn42/WinlatorXR-dawn-42.apk", latestApkName = "WinlatorXR-dawn-42.apk",
            latestApkSize = 971495156, latestBody = "# Shortened changelog\n\nThe project license has been changed to **GPLv3**.\n\n### Emulation Core\n* Add D7VK 2.3 as a DirectX 1-7 emulation option\n* Autoclose container changed to 5 seconds\n* Faster first-time installation\n* General emulation performance optimizations\n* Improved game compatibility in the default container\n* Improved handling of games stored on USB drives\n* Launch report from shortcut menu\n* Limited Turnip support for Quest 3/3S and Pico 3/4\n* Option to copy game files t"
        ),
        EmulatorEntry(
            id = "WinDroidEmulation-WinNative", name = "WinNative (fork)", category = "Winlator",
            description = "",
            owner = "WinDroidEmulation",
            repo = "WinNative",
            homepage = "", repoUrl = "https://github.com/WinDroidEmulation/WinNative",
            official = false, channel = "stable",
            downloads = 7110, stars = 0, pushedAt = "",
            latestTag = "build-2026-06-20", latestDate = "2026-06-20T00:47",
            latestApkUrl = "https://github.com/WinDroidEmulation/WinNative-Nightly/releases/download/build-2026-06-20/WinNative-Ludashi-release.apk", latestApkName = "WinNative-Ludashi-release.apk",
            latestApkSize = 552881553, latestBody = "Commit 381b54f\nView all upstream commits here: https://github.com/MaxsTechReview/WinNative/commits/main"
        ),
        EmulatorEntry(
            id = "NETHERSTRIKER-x1-box-apk-1.1.4-compiled-via-izzy2lost-source-code", name = "X1 BOX", category = "Xbox",
            description = "",
            owner = "NETHERSTRIKER",
            repo = "x1-box-apk-1.1.4-compiled-via-izzy2lost-source-code",
            homepage = "", repoUrl = "https://github.com/NETHERSTRIKER/x1-box-apk-1.1.4-compiled-via-izzy2lost-source-code",
            official = false, channel = "rc",
            downloads = 3138, stars = 16, pushedAt = "2026-03-08T18:10:12Z",
            latestTag = "X1Box", latestDate = "2026-03-08T18:10",
            latestApkUrl = "https://github.com/NETHERSTRIKER/x1-box-apk-1.1.4-compiled-via-izzy2lost-source-code/releases/download/X1Box/X1-Box.1.1.7.apk", latestApkName = "X1-Box.1.1.7.apk",
            latestApkSize = 24347928, latestBody = "Fixes mali issues"
        ),
        EmulatorEntry(
            id = "edeegg-sharpemu-arm64", name = "SharpEmu ARM64", category = "Emulator PS5",
            description = "",
            owner = "edeegg",
            repo = "sharpemu-arm64",
            homepage = "", repoUrl = "https://github.com/edeegg/sharpemu-arm64",
            official = false, channel = "stable",
            downloads = 2559, stars = 0, pushedAt = "",
            latestTag = "android-v0.0.2_poc", latestDate = "2026-08-05T07:38",
            latestApkUrl = "https://github.com/edeegg/sharpemu-arm64/releases/download/android-v0.0.2_poc/SharpEmu-Android-0.0.2_poc.apk", latestApkName = "SharpEmu-Android-0.0.2_poc.apk",
            latestApkSize = 91198273, latestBody = "Automated Android proof-of-concept build (0.0.2_poc)."
        ),
        EmulatorEntry(
            id = "Alexoqool-winlator-bionic-build", name = "Winlator Bionic Alexoqool", category = "Winlator",
            description = "APKs of Pipetto-crypto's Winlator Bionic",
            owner = "Alexoqool",
            repo = "winlator-bionic-build",
            homepage = "", repoUrl = "https://github.com/Alexoqool/winlator-bionic-build",
            official = false, channel = "stable",
            downloads = 2272, stars = 20, pushedAt = "2026-05-07T20:44:04Z",
            latestTag = "2026-05-03", latestDate = "2026-05-03T01:49",
            latestApkUrl = "https://github.com/Alexoqool/winlator-bionic-build/releases/download/2026-05-03/app-debug.apk", latestApkName = "app-debug.apk",
            latestApkSize = 519705074, latestBody = ""
        ),
        EmulatorEntry(
            id = "duckyduckG-winlator", name = "Winlator Bionic duckyduckG", category = "Winlator",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "duckyduckG",
            repo = "winlator",
            homepage = "", repoUrl = "https://github.com/duckyduckG/winlator",
            official = false, channel = "stable",
            downloads = 1759, stars = 10, pushedAt = "2026-05-17T23:18:19Z",
            latestTag = "8de79b1", latestDate = "2026-04-05T07:31",
            latestApkUrl = "https://github.com/duckyduckG/winlator/releases/download/8de79b1/app-debug.apk", latestApkName = "app-debug.apk",
            latestApkSize = 519704626, latestBody = ""
        ),
        EmulatorEntry(
            id = "winlatorbrasil-Exiled-Kingdoms-Multiplayer", name = "Exiled Kingdoms Multiplayer", category = "GAME",
            description = "Multiplayer",
            owner = "winlatorbrasil",
            repo = "Exiled-Kingdoms-Multiplayer",
            homepage = "", repoUrl = "https://github.com/winlatorbrasil/Exiled-Kingdoms-Multiplayer",
            official = false, channel = "stable",
            downloads = 1590, stars = 10, pushedAt = "2026-04-24T23:48:07Z",
            latestTag = "Exiled-Kingdoms-Multiplayer-2.0", latestDate = "2026-04-24T23:48",
            latestApkUrl = "https://github.com/gamehub-brasil/Exiled-Kingdoms-Multiplayer/releases/download/Exiled-Kingdoms-Multiplayer-2.0/Exiled-Kingdoms-Multiplayer-PVP-v1-3-1218-mod.apk", latestApkName = "Exiled-Kingdoms-Multiplayer-PVP-v1-3-1218-mod.apk",
            latestApkSize = 381595189, latestBody = "# 🛡️ [MOD] Exiled Kingdoms LAN Multiplayer — 2.0!\n\nHey everyone! Another update to the **LAN Multiplayer** mod for Exiled Kingdoms.\n\nHere's the full summary:\n\n---\n\n## ⚔️ NEW — LAN Multiplayer Bug Fixes\n\n### 🐛 Bug: Crash when entering Cities\n- **Problem:** When entering some cities, the game would crash and close.\n- **Fix:** Problem fixed, and you can now teleport and enter cities perfectly.\n\n### 🐛 Bug: Monsters ignored the CLIENT when the HOST was far away\n- **Problem:** Monsters "
        ),
        EmulatorEntry(
            id = "J4MCU-builds-Gamehub-Lite-RedMagic", name = "GameHub Lite (J4MCU-builds)", category = "GameHub",
            description = "This version of Gamehub Lite aims to reduce Input Latency and add Interpolated Frames for overall smoothness.",
            owner = "J4MCU-builds",
            repo = "Gamehub-Lite-RedMagic",
            homepage = "", repoUrl = "https://github.com/J4MCU-builds/Gamehub-Lite-RedMagic",
            official = false, channel = "stable",
            downloads = 713, stars = 2, pushedAt = "2026-02-26T13:11:42Z",
            latestTag = "V5.1.3", latestDate = "2026-02-18T00:49",
            latestApkUrl = "https://github.com/J4MCU-builds/Gamehub-Lite-RedMagic/releases/download/V5.1.3/GameHub-Lite-v5.1.3-RedMagic.apk", latestApkName = "GameHub-Lite-v5.1.3-RedMagic.apk",
            latestApkSize = 52999446, latestBody = "Fully Released build for Gamehub Lite 5.1.3 RedMagic. Feel free to help develop features!\n\nFeatures:\nCustom intro logo\nFrame generation\nLess touch latency\n\nGoing to add a check for updates feature for steam games in future versions!\n\nFrame generation only works for Devices with a Game Manager and support ME/MC, like the Honor 400 pro."
        ),
        EmulatorEntry(
            id = "deivid22srk-Winlator-Xmod", name = "Winlator Xmod", category = "Winlator",
            description = "",
            owner = "deivid22srk",
            repo = "Winlator-Xmod",
            homepage = "", repoUrl = "https://github.com/deivid22srk/Winlator-Xmod",
            official = false, channel = "stable",
            downloads = 537, stars = 12, pushedAt = "2025-10-18T17:16:22Z",
            latestTag = "Winlator_xmox_1.1.0", latestDate = "2025-09-20T16:27",
            latestApkUrl = "https://github.com/deivid22srk/Winlator-Xmod/releases/download/Winlator_xmox_1.1.0/app-debug_sign.apk", latestApkName = "app-debug_sign.apk",
            latestApkSize = 547286964, latestBody = "Changelog\n\nAlteração no layout\n\nAdicionada a possibilidade de baixar drivers Turnip pelo próprio app\n\nAdicionada a possibilidade de importar Wrapper pelos Contents (teste)\n\nAdicionada a possibilidade de instalar os Commoredist (Visual C++ + PhysX)\n\nAgora, ao importar o executável pela tela de atalho, ele extrai a capa do jogo (ainda não funciona para todos os jogos)\n\nAdição da seleção da topologia da CPU\n\n\n⚠️ O app ainda apresenta problemas na interface 😕\n\n🚨 Não ative o Native R"
        ),
        EmulatorEntry(
            id = "maxjivi05-GameNative-Performance", name = "GameNative Performance", category = "GameNative",
            description = "",
            owner = "maxjivi05",
            repo = "GameNative-Performance",
            homepage = "", repoUrl = "https://github.com/maxjivi05/GameNative-Performance",
            official = false, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "winlator-releases", name = "Winlator Bionic cjxyz", category = "Winlator",
            description = "",
            owner = "winlator",
            repo = "releases",
            homepage = "", repoUrl = "https://github.com/winlator/releases",
            official = false, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "eden-emulator-Releases", name = "Eden Emulator", category = "Nintendo Switch Emulator",
            description = "",
            owner = "eden-emulator",
            repo = "Releases",
            homepage = "", repoUrl = "https://github.com/eden-emulator/Releases",
            official = false, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "Eden-CI-Nightly", name = "Eden Emulator Nightly", category = "Nintendo Switch Emulator",
            description = "",
            owner = "Eden-CI",
            repo = "Nightly",
            homepage = "", repoUrl = "https://github.com/Eden-CI/Nightly",
            official = false, channel = "nightly",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "Citron-Emulator", name = "Citron Emulator", category = "Nintendo Switch Emulator",
            description = "",
            owner = "Citron",
            repo = "Emulator",
            homepage = "", repoUrl = "https://git.citron-emu.org/Citron/Emulator",
            official = false, channel = "nightly",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "ovsky-sumi-emu", name = "Sumi Emulator", category = "Nintendo Switch Emulator",
            description = "",
            owner = "ovsky",
            repo = "sumi-emu",
            homepage = "", repoUrl = "https://github.com/ovsky/sumi-emu",
            official = false, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
        ),
        EmulatorEntry(
            id = "Kenji-NX-Android-Releases", name = "Kenji-NX Emulator", category = "Nintendo Switch Emulator",
            description = "",
            owner = "Kenji-NX",
            repo = "Android-Releases",
            homepage = "", repoUrl = "https://github.com/Kenji-NX/Android-Releases",
            official = false, channel = "stable",
            downloads = 0, stars = 0, pushedAt = "",
            latestTag = "", latestDate = "",
            latestApkUrl = "", latestApkName = "",
            latestApkSize = 0, latestBody = ""
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
                val relArr = obj.optJSONArray("releases")
                val latest = if (relArr != null && relArr.length() > 0) relArr.getJSONObject(0) else null
                val assets = latest?.optJSONArray("assets")
                val firstAsset = if (assets != null && assets.length() > 0) assets.getJSONObject(0) else null
                val repoFull = obj.optString("repo")
                val slash = repoFull.indexOf('/')
                add(
                    EmulatorEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        category = obj.optString("category"),
                        description = obj.optString("description"),
                        owner = if (slash > 0) repoFull.substring(0, slash) else "",
                        repo = if (slash > 0) repoFull.substring(slash + 1) else "",
                        homepage = obj.optString("homepage"),
                        repoUrl = obj.optString("repo_url"),
                        official = obj.optBoolean("official", false),
                        channel = obj.optString("channel", "stable"),
                        latestTag = latest?.optString("tag") ?: obj.optString("latest_tag"),
                        latestDate = latest?.optString("date") ?: obj.optString("latest_date"),
                        latestBody = latest?.optString("body") ?: obj.optString("latest_body"),
                        latestApkUrl = firstAsset?.optString("url") ?: obj.optString("latest_apk_url"),
                        latestApkName = firstAsset?.optString("name") ?: "",
                        latestApkSize = firstAsset?.optLong("size", 0L) ?: 0L,
                        pushedAt = obj.optString("pushed_at"),
                        stars = obj.optLong("stars", 0L),
                        downloads = obj.optLong("downloads", 0L)
                    )
                )
            }
        }.filter { it.id.isNotBlank() }
    }
}
