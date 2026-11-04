# 生态候选草稿 · 20261104

> 从候选池取分 ≥85 的前 8 个，逐个抓取生成。
> 共 8 条（其中 0 条疑重复），抓取失败 0 个。

**每条的「怎么跑 / 跑在哪」都有证据行，对不上就改那一条，不用从头查。**

## 待人工确认（自动判定没把握的）

无

## 疑似同名不同物（建议直接跳过）

| id | 抓到的描述 |
|---|---|
| `slimeboyowo-lingchat` | Immersive AI-driven Galgame chat with emotional expressions, |

## MetroidPrime2Port  `odrannnn-metroidprime2port`

> Native port of Metroid Prime 2: Echoes (GameCube) for Linux and Android, built from the PrimeDecomp decompilation. No emulator, no game assets included: bring your own disc image.

| 项 | 值 |
|---|---|
| 仓库 | Odrannnn/MetroidPrime2Port |
| ★ / fork | 237 / 12 |
| 最近推送 | 2026-11-04T10:28:11Z |
| 许可 | NOASSERTION |
| 最新版 | v0.10.0（2026-11-03T20:03:00Z） |
| 判定状态 | active |
| 玩什么 | gamecube |
| 怎么跑 | recomp ← native port |
| 跑在哪 | android, windows, macos, linux, ios |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| metroid-prime2-port-0.10.0-android-arm64.apk | android | 18.1 MB | 284 |
| metroid-prime2-port-0.10.0-windows-x86_64.zip | windows | 61.3 MB | 512 |
| metroid-prime2-port-0.10.0-macos-arm64.zip | macos | 16.4 MB | 27 |
| metroid-prime2-port-0.10.0-linux-x86_64.tar.gz | linux | 18.2 MB | 53 |
| MetroidPrime2Port.ipa | ios | 14.0 MB | 18 |

**顶层文件**：.clang-format, .flake8, .gitattributes, .github, .gitignore, .gitmodules, .vscode, Add-Exclusion.ps1, CMakeLists.txt, LICENSE, LICENSE-decomp, NOTICE, PORT_NOTES.md, README.md, android, assets, cmake, config

**证据**：guest=['gamecube → gamecube']；hosts=['资产 metroid-prime2-port-0.10.0-android-arm64.apk → android', '资产 metroid-prime2-port-0.10.0-linux-aarch64.tar.gz 文件名含平台词 → linux', '资产 metroid-prime2-port-0.10.0-linux-x86_64.tar.gz 文件名含平台词 → linux', '资产 metroid-prime2-port-0.10.0-macos-arm64.zip 文件名含平台词 → macos']

## Tyranor Next  `weiss-ultimatesavior-tyranor-next`

> 多引擎视觉小说（Galgame）聚合启动器，面向 Android 平台。内置 Kirikiri / ONScripter / Tyrano / Artemis / Siglus / FVP 六套引擎运行环境，并支持 Ren'Py、RPG Maker RGSS 外置 APK 引擎模块与 PSP / Nintendo Switch 外置模拟器跳转（PPSSPP / Eden），可识别和启动多类游戏，提供游戏库管理、封面获取、存档镜像、引擎参数调节等一体化体验。

| 项 | 值 |
|---|---|
| 仓库 | Weiss-UltimateSavior/Tyranor-Next |
| ★ / fork | 781 / 33 |
| 最近推送 | 2026-11-04T04:28:17Z |
| 许可 | GPL-2.0 |
| 最新版 | beta-1.63（2026-11-04T04:28:17Z） |
| 判定状态 | active |
| 玩什么 | switch, psp, windows-pc, visual-novel |
| 怎么跑 | engine ← winlator |
| 跑在哪 | android |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| app-release.apk | android | 140.5 MB | 168 |

**顶层文件**：.archify, .coderabbit.yaml, .github, .gitignore, AGENTS.md, ARCHITECTURE.md, CHANGELOG.md, CONTEXT.md, CONTRIBUTING.md, LICENSE, README.md, SPEC.md, app, build.gradle.kts, buildSrc, docs, engine, gradle.properties

**证据**：guest=['switch → switch', 'ppsspp → psp', 'winlator → windows-pc', 'galgame → visual-novel']；hosts=['资产 app-release.apk → android']

## YukiHub  `xm486-yukihub`

> YukiHub is a Galgame management tool for Android that supports synchronization of local games, shortcuts, and play records. The project uses the GPL-3.0 open-source license.

| 项 | 值 |
|---|---|
| 仓库 | xm486/YukiHub |
| ★ / fork | 563 / 18 |
| 最近推送 | 2026-11-03T18:55:44Z |
| 许可 | GPL-3.0 |
| 最新版 | v0.4（2026-10-23T03:27:13Z） |
| 判定状态 | active |
| 玩什么 | visual-novel |
| 怎么跑 | None ← emulator 类目 |
| 跑在哪 | android |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| YukiHub_0.4.apk | android | 139.1 MB | 453 |

**顶层文件**：.github, .gitignore, CHANGELOG.md, CONTRIBUTING.md, LICENSE, README.md, README_EN.md, USER_GUIDE.md, app, build.gradle, gradle.properties, gradle, gradlew, gradlew.bat, keystore.properties.template, rfvp-build, screenshots, settings.gradle

**证据**：guest=['galgame → visual-novel']；hosts=['资产 YukiHub_0.4.apk → android']

## Most Wanted for Android  `ranggacasper-mostwanted-android`

> Unofficial native Android port of Need for Speed: Most Wanted (Xbox Black Edition). The x86 code is statically recompiled to C++ with XboxXBE-Recomp and built for arm64, with a Vulkan renderer, SDL3, touch/controller/keyboard input, and extended graphics options. No emulator, no game assets included.

| 项 | 值 |
|---|---|
| 仓库 | RanggaCasper/MostWanted-Android |
| ★ / fork | 222 / 24 |
| 最近推送 | 2026-11-01T14:43:58Z |
| 许可 | MIT |
| 最新版 | v1.3.0（2026-11-01T14:56:16Z） |
| 判定状态 | active |
| 玩什么 | xbox |
| 怎么跑 | recomp ← recompiled |
| 跑在哪 | android |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| MostWanted-1.3.0.apk | android | 32.2 MB | 63217 |

**顶层文件**：.gitattributes, .github, .gitignore, CLAUDE.md, CMakeLists.txt, LICENSE, README.id.md, README.md, app, build.bat, build.gradle, ci, config, gradle.properties, gradle, gradlew, gradlew.bat, native

**证据**：guest=['xbox → xbox']；hosts=['资产 MostWanted-1.3.0.apk → android']

## Aether  `aetherkiri-aether`

> A cross-platform, multi-runtime visual novel emulator using Godot 4 as the rendering layer.

| 项 | 值 |
|---|---|
| 仓库 | AetherKiri/Aether |
| ★ / fork | 201 / 15 |
| 最近推送 | 2026-11-04T10:52:25Z |
| 许可 | GPL-3.0 |
| 最新版 | 1.1.0（2026-11-03T08:27:42Z） |
| 判定状态 | active |
| 玩什么 | visual-novel |
| 怎么跑 | engine ← kirikiri |
| 跑在哪 | android, macos, linux, ios |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| AetherKiri-1.1.0-android.apk | android | 355.5 MB | 91 |
| AetherKiri-1.1.0-macos.zip | macos | 312.8 MB | 3 |
| AetherKiri-1.1.0-linux-x86_64.tar.gz | linux | 260.0 MB | 3 |
| AetherKiri-1.1.0-ios-app-store.ipa | ios | 262.2 MB | 5 |

**顶层文件**：.agents, .clang-format, .clang-tidy, .gitattributes, .github, .gitignore, .gitmodules, CMakeLists.txt, CMakePresets.json, CODE_OF_CONDUCT.md, COPYING.iOS, DESIGN.md, LICENSE, README.md, README.zh-CN.md, THIRD_PARTY_LICENSES.md, abi, apps

**证据**：guest=['visual novel → visual-novel']；hosts=['资产 AetherKiri-1.1.0-android.apk → android', '资产 AetherKiri-1.1.0-ios-app-store.ipa → ios', '资产 AetherKiri-1.1.0-ios-unsigned.ipa → ios', '资产 AetherKiri-1.1.0-linux-arm64.tar.gz 文件名含平台词 → linux']

## gears of war 2 pc port  `santiquiroz-gears-of-war-2-pc-port`

> Gears of War 2 native PC port: the Xbox 360 game statically recompiled to x64 (not an emulator). 1440p/4K, FSR, 4K texture packs, keyboard & mouse, Android in progress. Bring your own copy.

| 项 | 值 |
|---|---|
| 仓库 | santiquiroz/gears-of-war-2-pc-port |
| ★ / fork | 150 / 6 |
| 最近推送 | 2026-10-30T14:40:26Z |
| 许可 | BSD-3-Clause |
| 最新版 | v0.3.12（2026-10-30T14:41:12Z） |
| 判定状态 | active |
| 玩什么 | xbox360 |
| 怎么跑 | recomp ← recompiled |
| 跑在哪 | android, windows |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| gow2-recomp-0.3.12-android-arm64.apk | android | 66.4 MB | 3247 |
| gow2-recomp-0.3.12-win64.zip | windows | 79.6 MB | 1955 |

**顶层文件**：.gitattributes, .gitignore, BUILDING.md, CMakeLists.txt, CMakePresets.json, CONTRIBUTING.md, LICENSE, README.es.md, README.md, ROADMAP.md, THIRD_PARTY_NOTICES.md, android, assets, config, docs, gow2_manifest.toml, hints, notes

**证据**：guest=['xbox 360 → xbox360', 'xbox → xbox']；hosts=['资产 gow2-recomp-0.3.12-android-arm64.apk → android', '资产 gow2-recomp-0.3.12-win64.zip 文件名含平台词 → windows']

## LingChat  `slimeboyowo-lingchat`

> Immersive AI-driven Galgame chat with emotional expressions, desktop pet, scheduling, and interactive story modules. / 一款沉浸式 AI-Galgame 聊天软件，附带桌宠，日程，剧情功能

| 项 | 值 |
|---|---|
| 仓库 | SlimeBoyOwO/LingChat |
| ★ / fork | 2433 / 147 |
| 最近推送 | 2026-11-03T16:14:30Z |
| 许可 | AGPL-3.0 |
| 最新版 | v0.5.2（2026-09-13T05:25:27Z） |
| 判定状态 | active |
| 玩什么 | visual-novel |
| 怎么跑 | emulation ← 无明确证据，按默认 |
| 跑在哪 | android, windows, macos, linux |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| LingChat-v0.5.2-universal.apk | android | 219.3 MB | 3084 |
| LingChat_0.5.2_x64-setup.exe | windows | 164.8 MB | 3759 |
| LingChat_0.5.2_aarch64.dmg | macos | 178.5 MB | 138 |
| LingChat_0.5.2_amd64.AppImage | linux | 249.5 MB | 158 |

**顶层文件**：.cargo, .editorconfig, .gitattributes, .github, .gitignore, .husky, .npmrc, .prettierignore, .prettierrc.json, .vscode, LICENSE, data, docs, index.html, package.json, pnpm-lock.yaml, pnpm-workspace.yaml, public

**证据**：guest=['galgame → visual-novel']；hosts=['资产 LingChat-v0.5.2-universal.apk → android', '资产 LingChat_0.5.2_aarch64.AppImage → linux', '资产 LingChat_0.5.2_aarch64.dmg → macos', '资产 LingChat_0.5.2_amd64.AppImage → linux']

## LocationSpoofer  `huangzhuorui-locationspoofer`

> AwesomeDroid（原 LocationSpoofer）：基于 Android 17 的开发测试模拟器，提供定位、路线、传感器及无线环境模拟能力，面向自有或已授权应用的开发与调试。

| 项 | 值 |
|---|---|
| 仓库 | HuangZhuoRui/LocationSpoofer |
| ★ / fork | 2169 / 440 |
| 最近推送 | 2026-11-02T10:13:19Z |
| 许可 | NOASSERTION |
| 最新版 | v3.1.5（2026-11-02T10:13:19Z） |
| 判定状态 | active |
| 玩什么 | **未判定** |
| 怎么跑 | emulation ← emulator 类目 |
| 跑在哪 | android |
| 置信度 | high |

**资产**

| 文件 | 平台 | 大小 | 下载 |
|---|---|--:|--:|
| LocationSpoofer-v3.1.5-global-64bit-arm64-v8a.apk | android | 18.8 MB | 2188 |

**顶层文件**：LICENSE, README.md, README_EN.md

**证据**：guest=[]；hosts=['资产 LocationSpoofer-v3.1.5-global-32bit-armeabi-v7a.apk → android', '资产 LocationSpoofer-v3.1.5-global-64bit-arm64-v8a.apk → android']