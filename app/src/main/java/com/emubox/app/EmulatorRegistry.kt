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
            id = "azahar-emu-azahar", name = "Azahar", category = "console",
            description = "An open-source 3DS emulator project based on Citra.",
            owner = "azahar-emu", repo = "azahar", homepage = "https://azahar-emu.org/",
            latestTag = "2127.0-alpha1", latestApkUrl = "https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-android-googleplay-2126.2.apk",
            pushedAt = "2026-10-10T13:45:51Z", stars = 8324L, downloads = 8611904L,
            latestDate = "2026-10-08", latestBody = "<h3>⚠️Important Alpha Information⚠️</h3>\n\nAs an alpha release, this build is not yet considered stable.\n\nThis prerelease is intended for users to test upcoming features, allowing the community to provide feedback before they are distributed in a stable release.\nUsers interested in testing bleeding-edge software are encouraged to test out this build to help us catch any issues we may have missed during development.\n\nIf you are unwilling to tolerate potential issues, it may be better to wait for the next stable release.\n\n---\n\n> [!NOTE] \n> To allow public testing of the new interpreter, which allows fantastic performance on iPhones all the way back to the iPhone XS with no JIT, **we plan to distribute all of our 2127.0 prereleases on RetroArch with 2-3 days of delay** as a one-off exception to our typical release scheduling.\n> We hope our iOS users look forward to testing out the new version and experiencing the improved performance it brings.\n\n> [!NOTE] \n> This release invalidates any existing generated shader cache, so performance may temporarily be impared after updating.\n> Performance will improve again after playing for a while.\n\n---\n\n# Azahar 2127.0-alpha1 Changelog\n\n# License\n\n"
        ),
        EmulatorEntry(
            id = "weihuoya-citra", name = "Citra (weihuoya)", category = "console",
            description = "A Nintendo 3DS Emulator",
            owner = "weihuoya", repo = "citra", homepage = "https://citra-emu.org",
            latestTag = "20251112", latestApkUrl = "https://github.com/weihuoya/citra/releases/download/20251112/Citra_MMJ_20251112.apk",
            pushedAt = "2025-11-14T11:04:59Z", stars = 2762L, downloads = 8058376L,
            latestDate = "2025-11-12", latestBody = "**Before:**\nemulator directory: \"citra-emu\"\n\n**Antutu**:\npackage name: com.antutu.ABenchMark\nThis is tricking the os thinks it's running a benchmark app, and will boost performance on some device.\n\n**Storage Access:**\nemulator directory: \"Android/data/org.citra.emu/files/citra-emu\"\nWARNING uninstall citra will remove directory, include games, dlcs, save datas.\n\ndirectory: \"citra-emu\\nand\\data\\00000000000000000000000000000000\\sysdata\\00010026\\00000000\\CEC\"\nDelete this directory if game could not works as before.\n\n**Try reset settings** if game does not work properly."
        ),
        EmulatorEntry(
            id = "Vita3K-Vita3K-Android", name = "Vita3K Android", category = "console",
            description = "Public releases and CI for the Vita3K Android app",
            owner = "Vita3K", repo = "Vita3K-Android", homepage = "",
            latestTag = "v12", latestApkUrl = "https://github.com/Vita3K/Vita3K-Android/releases/download/v12/vita3k-android-release-12.apk",
            pushedAt = "2025-02-22T16:30:58Z", stars = 1666L, downloads = 4327222L,
            latestDate = "2024-06-06", latestBody = "Vita3K Android release 12 Changelog (non exhaustive list):\n- SD card support: Allow any folder to be specified as the OS/game data folder (inspired by the PR from nurunuruslime)\n- Allow the overlay to be used at the same time as the touchscreen\n- Fix black bands appearing sometimes at the top and bottom of the application on some devices\n- Fix bug causing game installation to be 10x slower than expected\n- Fix accelerometer and gyroscope on android game consoles\n- Some crash fixes\n- Add controller opacity (dima)\n\n\n\nRebase on PC version:\n- Fix audio regression (bookmist)\n- Code cleanup, refractoring and small optimisations (bookmist, Nishinji and GermanAizek)\n- Improved error messages (croden)\n- GUI improvements (Zangetsu)\n- Improve depth-stencil retrieval, fix Ratchet and Clank (Macdu)\n- Add support for module unloading/reloading, allows FFX to be playable (Macdu)\n- Improve ffmpeg dependency (KhoraLee)\n- Improve Transfer downscale/copy, fix graphical issues in Phantasy Star Nova (Macdu)\n- Improve Polish localization (mavethee)"
        ),
        EmulatorEntry(
            id = "Trixarian-NetherSX2-patch", name = "NetherSX2 Patch", category = "console",
            description = "Continuation of NetherSX2 based on AetherSX2 4248",
            owner = "Trixarian", repo = "NetherSX2-patch", homepage = "https://discord.gg/xRh3NnYyMa",
            latestTag = "2.2n", latestApkUrl = "https://github.com/Trixarian/NetherSX2-patch/releases/download/2.2n/NetherSX2-v2.2n-4248.apk",
            pushedAt = "2026-09-22T04:21:34Z", stars = 2679L, downloads = 3501608L,
            latestDate = "2025-12-25", latestBody = "Merry Christmas, Happy Holidays and a Happy New Year everyone!\n\nThis update adds experimental Online Multiplayer to NetherSX2 along with the usual updates!\nThanks to [IamLupo](https://github.com/IamLupo) for adding it to ARMSX2, and [zakilj3](https://www.youtube.com/user/zakilj3) for discovering the same settings work with NetherSX2!\n\n[VirusTotal Scan Report](https://www.virustotal.com/gui/file/2029e30dcc1ba1ecb8d73313f18b429b40e6044c7785c52af08b948ef94b3ddf)\n\n## How to use Online Multiplayer\n1. Go to your App Settings --> Settings and toggle on Enable DEV9 Ethernet\n2. Set API to Sockets, and Device to how you want to connect online with the default being WiFi. Set this to VPN if your phone uses a VPN, and to one of the two SIM DATA options if you want to use your Mobile Data Plan instead\n3. That should be enough to get you online using a guide like [this](https://youtu.be/dcOnZzzcbSc?si=WOf01r0Ln5ZVQkpf&t=278) by LogicalMacrochip\n\nYou can use the [Official List of PlayStation 2 Online Games (2024)](https://docs.google.com/spreadsheets/d/15pfhL36qmC-iKr8MNjNXNLwgacG4VOmkfvwVxV5vGzo/edit?gid=0#gid=0) to figure out what games work, get their DNAS bypass codes if needed and which priv"
        ),
        EmulatorEntry(
            id = "SSimco-Cemu", name = "Cemu", category = "console",
            description = "Android port for the cemu emulator",
            owner = "SSimco", repo = "Cemu", homepage = "",
            latestTag = "0.5", latestApkUrl = "https://github.com/SSimco/Cemu/releases/download/0.5/Cemu-0.5.apk",
            pushedAt = "2026-09-12T17:30:42Z", stars = 1205L, downloads = 2873874L,
            latestDate = "2026-04-25", latestBody = "Experimental release 0.5\n\n- Implemented options to enable emulated USB devices & configure them"
        ),
        EmulatorEntry(
            id = "StevenMXZ-Winlator-Ludashi", name = "Winlator Ludashi", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "StevenMXZ", repo = "Winlator-Ludashi", homepage = "https://winlator.org",
            latestTag = "v4.1", latestApkUrl = "https://github.com/StevenMXZ/Winlator-Ludashi/releases/download/v4.1/ludashi-build.apk",
            pushedAt = "2026-10-07T21:29:08Z", stars = 1225L, downloads = 1528735L,
            latestDate = "2026-09-13", latestBody = "What's new:\n\n\\- Fixed some games showing a black screen due to incorrect surface format. (like RDR2)\n\\- Updated DisplayX.\n\\- Now works in container mode.\n\\- Added two new options: submit every buffer and precise presentation.\n\\- added D7VK\n\\- Fixed X11 bypass not working on DX12.\n\\- improved Freedreno compatibility \n\\- Various bug fixes, improved stability, and some UI changes based on feedback.\n\n📌 update requires reinstallation"
        ),
        EmulatorEntry(
            id = "Trixarian-NetherSX2-classic", name = "NetherSX2 Classic", category = "console",
            description = "Continuation of NetherSX2 based on AetherSX2 3668",
            owner = "Trixarian", repo = "NetherSX2-classic", homepage = "https://discord.gg/xRh3NnYyMa",
            latestTag = "2.2n", latestApkUrl = "https://github.com/Trixarian/NetherSX2-classic/releases/download/2.2n/NetherSX2-v2.2n-3668.apk",
            pushedAt = "2026-06-29T12:01:14Z", stars = 1200L, downloads = 1506976L,
            latestDate = "2025-12-25", latestBody = "Merry Christmas, Happy Holidays and a Happy New Year everyone!\n\nThis update adds experimental Online Multiplayer to NetherSX2 along with the usual updates!\nThanks to [IamLupo](https://github.com/IamLupo) for adding it to ARMSX2, and [zakilj3](https://www.youtube.com/user/zakilj3) for discovering the same settings work with NetherSX2!\n\n[VirusTotal Scan Report](https://www.virustotal.com/gui/file/8b502b31ce797d5a162bc6b9300b5f02351b0b6b290b8dc1390b8dfb826673ab)\n\n## How to use Online Multiplayer\n1. Go to your App Settings --> Settings and toggle on Enable DEV9 Ethernet\n2. Set API to Sockets, and Device to how you want to connect online with the default being WiFi. Set this to VPN if your phone uses a VPN, and to one of the two SIM DATA options if you want to use your Mobile Data Plan instead\n3. That should be enough to get you online using a guide like [this](https://youtu.be/dcOnZzzcbSc?si=WOf01r0Ln5ZVQkpf&t=278) by LogicalMacrochip\n\nYou can use the [Official List of PlayStation 2 Online Games (2024)](https://docs.google.com/spreadsheets/d/15pfhL36qmC-iKr8MNjNXNLwgacG4VOmkfvwVxV5vGzo/edit?gid=0#gid=0) to figure out what games work, get their DNAS bypass codes if needed and which priv"
        ),
        EmulatorEntry(
            id = "Producdevity-gamehub-lite", name = "GameHub Lite (Producdevity)", category = "windows",
            description = "GameHub Lite is a community-maintained modified version of GameHub for education purposes",
            owner = "Producdevity", repo = "gamehub-lite", homepage = "https://www.emuready.com/",
            latestTag = "v5.1.8", latestApkUrl = "https://github.com/Producdevity/gamehub-lite/releases/download/v5.1.8/GameHub-Lite-v5.1.8-alt-antutu.apk",
            pushedAt = "2026-07-03T18:28:52Z", stars = 2041L, downloads = 1478561L,
            latestDate = "2026-07-02", latestBody = "## Security: Steam token redaction in logs\n\nGameHub Lite 5.1.8 fixes a logging issue where Steam authentication-related values could appear in diagnostic logs. \nIn some cases, logs could include fields such as `steamToken`, `refreshToken`, or `accessToken` from Steam login/launch flows.\n\n**Publicly posted logs containing these fields should be deleted or redacted.**\n\n### IMPORTANT\n\nThis should go without saying but If you find any security issues, please reach out **PRIVATELY**. \nI have not checked if this issue is still present in recent GameHub versions, already ruined my one day off I was spending with my family by the person who reported this publicly and I do not plan on spending any more time on this.\n\n### Changelog\n\nThe fix adds centralized log redaction for Steam/auth token fields, Steam QR login URLs, JWT-like token strings, and launch command token arguments before logs are written. This covers the app loggers, JavaSteam logging, and the PC launch-log file writer.\n\nThis release also pins local patch builds to `apktool 2.12.1`\n\n**Full Changelog**: https://github.com/Producdevity/gamehub-lite/compare/v5.1.7...v5.1.8"
        ),
        EmulatorEntry(
            id = "longjunyu2-winlator", name = "Winlator Glibc", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "longjunyu2", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "v7.1.5", latestApkUrl = "https://github.com/longjunyu2/winlator/releases/download/v7.1.3/app-release.apk",
            pushedAt = "2026-01-05T23:43:15Z", stars = 551L, downloads = 1023386L,
            latestDate = "2024-08-29", latestBody = "## Warning! This APK is signed by a different key. This means you will have to uninstall the 7.1.3 version first (which will delete all your data!)\n\n## What's New\n#### Added\n- Alsa sound driver updated #183\n- Multikey touch button binding #197\n- Touch haptic when virtual keyboard or controller is enabled #62\n  \n#### Fixed\n- Invisible mouse cursor in some games #192\n- Some 32 bit games not working properly #173\n  \n####  What's new about OpenXR Support?\n- Fullscreen is forced only in immersive VR mode\n- Set Alsa as default audio driver"
        ),
        EmulatorEntry(
            id = "ARMSX2-ARMSX2", name = "ARMSX2", category = "console",
            description = "ARMSX2 - The Playstation 2 Emulator for ARM64 Platforms",
            owner = "ARMSX2", repo = "ARMSX2", homepage = "https://armsx2.net/",
            latestTag = "2.8.3", latestApkUrl = "https://github.com/ARMSX2/ARMSX2/releases/download/2.8.3/ARMSX2-2.8.3-a11-armv8.2-sdk30.apk",
            pushedAt = "2026-10-11T21:12:33Z", stars = 2159L, downloads = 923463L,
            latestDate = "2026-10-11", latestBody = "# ARMSX2 2.8.3\n\nWhat's new: \n\n## MaliSX2 for Mali GPUs\n\nMaliSX2 is a Vulkan driver for Mali GPUs that @bmdhacks built for ARMSX2. In 2.8.2 it was offered only on the Mali-G615 and G715, under its old name, libmali. Now it also covers the Mali-G57, G68, G77 and G78 (including the G78AE), which a lot of handhelds use, the Anbernic RG 476H among them.\n\n<img width=\"2340\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/884734d3-d2b8-4651-a87c-1149d34c2c9b\" />\n\nWhat it does for a game: on @DIB1998 Mali G57, Ace Combat 4 and Ace Combat 5 were unplayable on the stock driver. With ARMSX2's driver MaliSX2 0.1.4 both run at full speed, as well as many other games that previously did not.\n\n<img width=\"2340\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/80fd3194-a831-4603-890f-e7e5fa296d0d\" />\n\n<img width=\"2340\" height=\"1080\" alt=\"image\" src=\"https://github.com/user-attachments/assets/543d4a85-83ec-4a17-b71c-7287369441b0\" />\n\nOn those GPUs:\n- The GPU driver list (Renderer settings, or the in-game menu) offers MaliSX2 to download.\n- Auto now picks Vulkan.\n- If Vulkan ends up on Arm's driver instead, a notice tells you to get MaliSX2.\n- With M"
        ),
        EmulatorEntry(
            id = "flyinghead-flycast", name = "Flycast", category = "console",
            description = "Flycast is a multiplatform Sega Dreamcast, Naomi, Naomi 2 and Atomiswave emulator",
            owner = "flyinghead", repo = "flycast", homepage = "",
            latestTag = "v2.7", latestApkUrl = "https://github.com/flyinghead/flycast/releases/download/v2.7/flycast-2.7.apk",
            pushedAt = "2026-10-10T01:44:52Z", stars = 2587L, downloads = 880608L,
            latestDate = "2026-08-19", latestBody = "## New features\n* Frame rate detection and pacing\n* Localization (fr, hu, jp, pt_BR, sv)\n* Multiscreen support for Airline Pilot, Sega Strike Fighter and Derby Owners Club\n* Improved modem emulation\n* improved DreamPicoPort and DreamPotato support by @RikkiGibson, @Tails86 and @nexus382\n* Android: Google Auto Backup support for VMU saves and non-volatile memory\n\n## Fixed Games\n* Airline Pilot (Naomi, multiscreen)\n* Cyber Troopers Virtual-On: Oratorio Tangram (JP, online features)\n* Derby Owners Club (new)\n* Dinosaur King (ZH, new)\n* DreamParaPara by @dakrk (new)\n* F355 Challenge (JP, online features)\n* Fish Life (new)\n* Love & Berry 3 (ZH, new)\n* NFL 2K1, NFL 2K2, NBA 2K1, NBA 2K2 (freeze)\n* Power Smash (online lag)\n* Rune Jade (online features)\n* Samba de Amigo (Naomi, inputs)\n* Sega Strike Fighter (Naomi, multiscreen)\n\n## New Contributors\n* @Windsurf7 made their first contribution in https://github.com/flyinghead/flycast/pull/2229\n* @Kyuyrii made their first contribution in https://github.com/flyinghead/flycast/pull/2236\n* @eadmaster made their first contribution in https://github.com/flyinghead/flycast/pull/2252\n* @PLCodingStuff made their first contribution in https://github.co"
        ),
        EmulatorEntry(
            id = "RPCSX-rpcsx-ui-android", name = "RPCSX Android", category = "console",
            description = "",
            owner = "RPCSX", repo = "rpcsx-ui-android", homepage = "",
            latestTag = "v20250425", latestApkUrl = "https://github.com/RPCSX/rpcsx-ui-android/releases/download/v20250425/rpcsx-release.apk",
            pushedAt = "2026-08-29T13:27:19Z", stars = 1330L, downloads = 851205L,
            latestDate = "2025-04-25", latestBody = "**Full Changelog**: https://github.com/RPCSX/rpcsx-ui-android/compare/v20250417...v20250425"
        ),
        EmulatorEntry(
            id = "coffincolors-winlator", name = "Winlator Coffincolors", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "coffincolors", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "cmod_v13.1", latestApkUrl = "https://github.com/coffincolors/winlator/releases/download/cmod_v13.1/Winlator-Cmod-v13.1.1.apk",
            pushedAt = "2025-12-09T22:36:49Z", stars = 1115L, downloads = 844495L,
            latestDate = "2025-08-07", latestBody = "_Minor update v13.1.1 fixed custom icons, default renderer, and warning prompt handling on new installs_ \n\n------------------------------\n\n-   **`evshim` Rewrite:** The entire controller shim (`libevshim.so`) has been rewritten, **completely eliminating the use of shared memory (`mmap`)**, resulting in a massive reduction in CPU overhead and a significant performance boost, especially on lower-end devices. Box64 3.2 and 3.6 are now compatible, and have been restored.\n\n-   **ALSA-Reflector Improved** The pacer's ring buffer and reconnection logic have been refined, expect better performance and reliability.\n\n-   **Universal CPU Overhead Reduction:** I've gone through the code to find and eliminate performance bottlenecks, once again, big thanks to @Pipetto-crypto as always for the guidance and his improvements to the Graphics Wrapper. \n\n### ✨ New Feature: VkBasalt Integration\n\n-   **Per-Shortcut Sharpening & Effects:** Thanks to @Pipetto-crypto You can now enable VkBasalt on a per-shortcut basis to apply CAS and DLS to your games.\n\n### 🐞 Stability & Bug Fixes:\n\nThis update includes a huge number of fixes for a smoother experience.\n\n-   **Corrupted Shortcut Crash Fixed:** Implemented"
        ),
        EmulatorEntry(
            id = "afeimod-winlator-mod", name = "Winlator Afei", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "afeimod", repo = "winlator-mod", homepage = "https://winlator.org",
            latestTag = "10.1hotfix-Amod3fix2", latestApkUrl = "https://github.com/afeimod/winlator-mod/releases/download/10.1hotfix-Amod3fix2/Winlator10.1-Amod3fix2.apk",
            pushedAt = "2026-04-19T02:53:06Z", stars = 698L, downloads = 820488L,
            latestDate = "2025-12-02", latestBody = "1-修复部分游戏mod失效，比如死或生5，上古卷轴五等\n2-提供wine-10.15.whp可以在额外内容里安装，这个wine可以播放一些视频白屏有声音的游戏\n3-其他更新见fix1\n"
        ),
        EmulatorEntry(
            id = "Medard22-Dolphin-MMJR2-VBI", name = "Dolphin MMJR2 VBI", category = "console",
            description = "A fork of Dolphin MMJR2 - with added VBI Skip hack and further fixes ported over from Dolphin Official.",
            owner = "Medard22", repo = "Dolphin-MMJR2-VBI", homepage = "",
            latestTag = "U24.07.02-2407", latestApkUrl = "https://github.com/Medard22/Dolphin-MMJR2-VBI/releases/download/U24.07.02-2407/Dolphin.MMJR2-VBI-U24.07.02-2407.apk",
            pushedAt = "2025-08-03T09:27:46Z", stars = 278L, downloads = 773815L,
            latestDate = "2025-07-20", latestBody = "Notable changes from U24.03.22-21259\n\n- Code refactoring and minor fixes\n- Fixed a panic alert for Def Jam: Fight for New York and Fifa Street\n- Numerous small optimizations to JitARM64\n- Small optimizations to DSP HLE audio decoding\n- Fixed stuttering from JIT Cache clear - mostly notable in Shadow the Hedgehog\n- Set OSD message color to yellow in default AMOLED theme\n- Updated Vulkan Memory Allocator and Vulkan Headers\n- Updated and optimized app icons\n\nMissing features - these features from Dolphin Official are not implemented\n\n- Android Input Overhaul and subsequent changes to Input and TAS handling\n- Custom GPU driver support\n- Skylanders portal and Infinity base support\n- Gamma correction\n\nThis codebase is based on the Official Dolphin dev build 2407-1 (Jul 2, 2024). For more information about what has changed check the PR history. \n\n**General information**\n- When I started this project, my only goal was to add the VBI Skip hack to MMJR2. At the time, I had little to no coding experience and knew nothing about Git, GitHub, or Android Studio. Looking back to 2023, I never imagined I’d come this far.\n- Fast forward almost a year and a half later—with over 1,100 pull requests—I’"
        ),
        EmulatorEntry(
            id = "xodiosx-XoDos", name = "XoDos", category = "windows",
            description = "",
            owner = "xodiosx", repo = "XoDos", homepage = "",
            latestTag = "v6.0.0+c050ebd", latestApkUrl = "https://github.com/xodiosx/XoDos/releases/download/v6.0.0/XoDos-6.0.0-full_arm64-v8a.apk",
            pushedAt = "2026-05-04T03:29:50Z", stars = 740L, downloads = 755643L,
            latestDate = "2025-10-28", latestBody = "**XoDos-6.0.0.r4 APKs**\n💝 Install over the old APK to UPDATE \n\n🎆Happy holidays 🎊\nThis going to be the last update for the development would be paused until further notice thanks for everyone support this project was a blessing, because All those who Loved and support it till today,, and maybe if google Changed it's 2026 policy against developers we can see more and more awesome projects from GitHub and other development places to improve android community and make it great again,,,♥️\n \n✅Fixed shortcuts manger to delete shortcuts\n✅ Removed PIP mode (picture in picture) it's crashing for some android systems\n✅Changed xodos system installing as optional \n✅Fixed gamepad support and key bindings\n\n## Note :⚠️\nTo use shortcuts manger with other emulators like mobox\nInstall the necessary packages for it to work probably,,\n```\npkg install icoutils imagemagick -y\n```\n\n\n**Full Changelog**: https://github.com/xodiosx/XoDos/compare/v6.0.0+391a1a5...v6.0.0+c050ebd"
        ),
        EmulatorEntry(
            id = "aenu1-aps3e", name = "APS3e", category = "console",
            description = "An experimental Android PS3 emulator, ported and optimized based on RPCS3. ",
            owner = "aenu1", repo = "aps3e", homepage = "https://aenu.cc/aps3e",
            latestTag = "2.43", latestApkUrl = "https://github.com/aenu1/aps3e/releases/download/2.43/2.43.apk",
            pushedAt = "2026-10-05T09:08:05Z", stars = 738L, downloads = 735037L,
            latestDate = "2026-10-05", latestBody = ""
        ),
        EmulatorEntry(
            id = "Fcharan-WinlatorMali", name = "Winlator Mali", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "Fcharan", repo = "WinlatorMali", homepage = "https://winlator.org",
            latestTag = "bionic-mali-1.1", latestApkUrl = "https://github.com/GunaCharanTeja/WinlatorMali/releases/download/bionic-mali-1.1/Winlator-Mali-1.1-Antutu.apk",
            pushedAt = "2026-10-04T12:44:25Z", stars = 440L, downloads = 640224L,
            latestDate = "2026-07-09", latestBody = "Winlator Mali Bionic v1.1\n\n🚀 New Features\n\n- Added Fake HDR.\n- Added a Universal FPS Limiter.\n- Added a Sharpness option.\n- Added GTA V Optimization toggle in Shortcut Settings.\n- Added DXVK Config toggle with built-in pre-made configuration presets.\n- Automatically generates a dxvk.conf file for each container based on the selected preset.\n- Added new DXVK versions.\n- Added the new GameNative Vulkan Wrapper.\n- Added new Box64 and FEX presets.\n\n---\n\n🎮 Unreal Engine 3 Preset\n\n- Added a dedicated Unreal Engine 3 preset.\n- Tested with X-Men Origins: Wolverine for improved compatibility and performance.\n\n---\n\n⚙️ Hybrid x86 FEXCore\n\n- Added a custom Hybrid x86 FEXCore.\n\nWhat does it do?\n\n- Improves compatibility while maintaining high performance.\n- Fixes physics issues in supported games that normally require x86 Reduced Precision to be completely disabled.\n\nWhy is it better?\n\nNormally, disabling x86 Reduced Precision forces software emulation for all x86 code, which can significantly reduce performance.\n\nHybrid x86 only uses software emulation where it is actually needed (such as physics calculations), while allowing the rest of the game to run natively for much better performance.\n\nT"
        ),
        EmulatorEntry(
            id = "HorizonEmuTeam-Horizon-Emu", name = "Horizon Emu", category = "windows",
            description = "",
            owner = "HorizonEmuTeam", repo = "Horizon-Emu", homepage = "",
            latestTag = "v4", latestApkUrl = "https://github.com/HorizonEmuTeam/Horizon-Emu/releases/download/v4/HorizonEmu4.NotConnectedFix.apk",
            pushedAt = "2025-02-28T07:48:56Z", stars = 717L, downloads = 486738L,
            latestDate = "2025-02-28", latestBody = "After minor changes like Box64, Wine (all have almost the same performance), DXVK and Turnip and me not having time to do anything with this project, it's finally getting a \"Not Connected\" fix related to the usage of /tmp directory on some devices by the termux-x11 library in the app. Should work on Android 15 with this fix as well, people already reported it working"
        ),
        EmulatorEntry(
            id = "The412Banner-bannerhub", name = "BannerHub (The412Banner)", category = "windows",
            description = "GameHub ReVanced — extended with GOG, Epic & Amazon game libraries, Component Manager, Winlator HUD, per-game controls & configs, and more. Built via apktool smali patching.",
            owner = "The412Banner", repo = "bannerhub", homepage = "",
            latestTag = "v3.8.1", latestApkUrl = "https://github.com/The412Banner/BannerHub/releases/download/v3.8.1/BannerHub-v3.8.1-alt-AnTuTu.apk",
            pushedAt = "2026-09-28T21:24:36Z", stars = 529L, downloads = 363297L,
            latestDate = "2026-09-12", latestBody = "> [!WARNING]\n> **Only install official stable releases** (like this one). Do NOT install pre-releases or raw CI artifacts — pre-release builds use a different package name (`com.tencent.ig`) and cannot be upgraded to stable without uninstalling first.\n\n> [!IMPORTANT]\n> **Microphone permission required for Voice Chat.** When you activate voice chat (dashboard → Game Configs → Voice Chat), BannerHub will prompt for the **Microphone** permission. You must allow it for in-game calls to work — without it the call falls back to opening in your browser.\n\n> [!IMPORTANT]\n> **Notification permission required.** BannerHub will prompt for the **Post Notifications** permission when you start your first Epic, GOG, or Amazon download. You must allow it to receive download progress and completion notifications.\n\n> [!IMPORTANT]\n> **Files access required for SD card storage.** To save games to your SD card, BannerHub must be granted the **Files and Media** permission (Android Settings → Apps → BannerHub → Permissions → Files and media → Allow management of all files). Without it the SD card toggle has no effect.\n\n## 🛠️ Games work on the new GameHub firmware (1.4.8 / 1.4.9)\n\nWith the **GameHub** API "
        ),
        EmulatorEntry(
            id = "winebox64-winlator", name = "Wb64dev", category = "windows",
            description = "Android application for running Windows applications with Wine and Box86/Box64",
            owner = "winebox64", repo = "winlator", homepage = "https://winlator.org",
            latestTag = "v10.2", latestApkUrl = "https://github.com/winebox64/winlator/releases/download/v10.2/winlator.apk",
            pushedAt = "2025-03-18T01:28:27Z", stars = 535L, downloads = 296786L,
            latestDate = "2025-03-03", latestBody = "[![logo](https://github.com/user-attachments/assets/bd81f1a1-2391-4c39-abbd-a42b1f5ef65f)](https://github.com/winebox64/winlator/releases)\n\n\n<p align=\"left\">\n<img  src=\"https://img.shields.io/badge/Winlator%2010.2-white?style=plastic&logo=snapdragon&logoColor=C00000\" width=\"140\" /> \n</p>\n\n# Released for personal use\n\n### Winlator 10.2 (beta)\n- Now smaller, lighter, and faster! Save storage with optimized app packaged in 130MB and installed in just 1.3GB.\n- Updated latest DXVK Async (v2.6.1), and **DXVK ULL (v2.5.3.94)** focus on ultra low latency ~8ms at 60 FPS, ~4ms at 120 FPS, with slightly lower fps (small performance impact), Improves input responsiveness on NFS: Most Wanted 2012.\n![lv_0_20250323083625](https://github.com/user-attachments/assets/a0aab4ec-0637-4cd2-a171-8ad5a76d91cb)\n**Definition:**\n*What is ULL? Ultra-low latency refers to an extremely short delay or lag between a user's input and the system's response. It's measured in milliseconds (ms) or even nanoseconds (ns).*\n\n| *Input Latency* | *Gaming Experience* | *Suitability* |\n| --- | --- | --- |\n| 0-30 ms | Excellent | Competitive Gaming |\n| 30-50 ms | Good | Casual Gaming |\n| 50-100 ms | Fair | Non-Competitive Pla"
        ),
        EmulatorEntry(
            id = "Droid-Deck-DroidDeck", name = "DroidDeck", category = "windows",
            description = "DroidDeck brings the SteamOS experience to Android",
            owner = "Droid-Deck", repo = "DroidDeck", homepage = "",
            latestTag = "0.3.2", latestApkUrl = "https://github.com/Droid-Deck/DroidDeck/releases/download/0.3.2/DroidDeck-0.3.2-antutu.apk",
            pushedAt = "2026-10-12T04:27:43Z", stars = 4974L, downloads = 245220L,
            latestDate = "2026-10-08", latestBody = "\n### Features\n\n- Added **Keep downloads running** toggle, so Steam downloads can continue when Android sleeps. (@jmarti326, @xXJSONDeruloXx, [#335](https://github.com/Droid-Deck/DroidDeck/pull/335))\n\n  <img src=\"https://raw.githubusercontent.com/Droid-Deck/DroidDeck/0.3.2/docs/releases/media/0.3.2/keep-downloads-running.gif\" width=\"50%\" alt=\"Closing the handheld mid-download and opening it again: Steam's download keeps going while asleep and finishes with the lid shut\">\n\n- Added **Native Steam sleep** with dynamic cloud save and wake movie support. (@xXJSONDeruloXx, [#291](https://github.com/Droid-Deck/DroidDeck/pull/291))\n\n  <img src=\"https://raw.githubusercontent.com/Droid-Deck/DroidDeck/0.3.2/docs/releases/media/0.3.2/native-steam-sleep.gif\" width=\"50%\" alt=\"Octopath Traveler II on the title screen, the device going to sleep, then waking to Steam's wake movie and landing back in the game\">\n\n- Added **Adreno 6xx support**, including Adreno 650 (eg: Retroid Pocket 5). (@unbelievableflavour, @jmarti326, [#320](https://github.com/Droid-Deck/DroidDeck/pull/320), [#326](https://github.com/Droid-Deck/DroidDeck/pull/326))\n- SD cards and external drives can now be used as full **Steam li"
        ),
        EmulatorEntry(
            id = "The412Banner-bannerhub-revanced", name = "BannerHub v6 (The412Banner)", category = "windows",
            description = "ReVanced patches for GameHub — BannerHub features as true ReVanced patches",
            owner = "The412Banner", repo = "bannerhub-revanced", homepage = "",
            latestTag = "v1.0.2-631", latestApkUrl = "https://github.com/The412Banner/bannerhub-revanced/releases/download/v1.0.2-631/BannerHub-V6-1.0.2-631-Patched-alt-AnTuTu.apk",
            pushedAt = "2026-10-11T15:49:11Z", stars = 176L, downloads = 213428L,
            latestDate = "2026-09-27", latestBody = "## BannerHub v6 1.0.2-631\n\n> ⚠️ **Read before installing — USE AT YOUR OWN RISK.** BannerHub v6 is a **separate project** from BannerHub 3.7.x and BannerHub Lite (own package names, keystore, and backend) — they do **not** update over one another, and v6 is a **work in progress, barely tested**, with different game compatibility. Full details below. ⤵\n\n<details>\n<summary><strong>⚠️ Important — separate projects &amp; compatibility (click to expand)</strong></summary>\n\n**BannerHub v6 does NOT replace BannerHub 3.7.x or BannerHub Lite — they are SEPARATE projects.**\n\n- **BannerHub** 3.7.x — built from the GameHub 5.3.5 ReVanced project by **PlayDay**.\n- **BannerHub Lite** — built from GameHub Lite 5.1.4 by **Producdevity**.\n- **BannerHub v6** *(this repo)* — built from XiaoJi GameHub 6.0.x on a brand-new pipeline.\n\n**None of the three are to be updated over by any of the others.** Each ships with its own package names, its own keystore, and its own component / Steam-client backend — Android won't accept an in-place update between them, and forcing one will leave you with a broken install. Uninstall first, then install the new project if you want to switch.\n\nKeep in mind **BannerHub v"
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
