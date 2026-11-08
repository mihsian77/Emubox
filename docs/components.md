# 组件 / 运行库 / 驱动：三套数据各是什么

> 2026-11-08 核对。改数据源先改这里。

App 里三个分区看着相近，实际是三套互不相干的数据。

| 分区 | 数据从哪来 | 谁在更新 | 条目规模 |
|---|---|---|---|
| **驱动** | `driver-registry.json` 登记仓库 + 各仓 GitHub releases | 仓库每天 02:40 | 52 个仓库 |
| **组件** | 6 份社区 `contents.json` 清单，App 运行时直连 | 清单维护者发新构建 | 536 个版本名（去重） |
| **运行库** | `The412Banner/winlator-contents` 的 `components.json` + 6 条微软直链 | **人工补录，不自动跟踪** | 124 条 |

组件清单来源（`SourceCatalog.kt` 的 `componentSources`）：

| id | 名称 | 清单 |
|---|---|---|
| `winnative` | WinNative-Emu | `WinNative-Emu/Components` 的 `contents.json` |
| `xnick` | Xnick Nightly | `nicholasx417/WinNative-Components` 的 `contents.json` |
| `banner-components` | The412Banner | `The412Banner/winlator-contents` 的 `contents.json` |
| `ref4ik` | REF4IK | `REF4IK/Components-Adrenotools-` 的 `contents.json` |
| `arihany` | Arihany WCP Hub | `Arihany/WinlatorWCPHub` 的 `pack.json` |
| `stevenmxz-wcp` | StevenMXZ WCP | `StevenMXZ/Winlator-Contents` 的 `contents.json` |

## 一、组件的 8 个类型

| 类型 | 上游仓库 | 上游最新（核对日） | news 是否跟 | 清单里版本名 |
|---|---|---|---|---|
| DXVK | `doitsujin/dxvk` | v3.1.1 | ✅ release | 223 |
| VKD3D | `HansKristian-Work/vkd3d-proton` | v3.1 | ✅ release | 54 |
| D7VK | `WinterSnowfall/d7vk` | v2.4 | ✅ release | 23 |
| FEXCore | `FEX-Emu/FEX` | FEX-2611 | ✅ release | 72 |
| Box64 | `ptitSeb/box64` | v0.4.4 | ✅ release | 38 |
| WOWBox64 | `butilly/box64`（同步 ptitSeb） | tag `v0.4.5-1` | ✅ tag | 30 |
| Proton | `ValveSoftware/Proton` | proton-11.0-2 | ✅ release | 54 |
| | `GloriousEggroll/proton-ge-custom` | GE-Proton11-12 | ✅ release | （并入 Proton） |
| Wine | `dl.winehq.org` 源码目录 | 11.21 | ✅ 目录源 | 66 |

附：Wine Mono / Wine Gecko（dl.winehq.org）、Mono（mono-project.com）也算组件区的附加项。

## 二、分支与构建变体

清单里的版本名带后缀，后缀就是构建口径，同一个上游版本会有多份。

**DXVK（223 个版本名）**

| 变体 | 数量 | 说明 |
|---|---|---|
| base | 65 | 官方 release + 普通 arm64 构建 |
| gplasync | 49 + 38（arm64ec） | 异步管线编译，降卡顿 |
| sarek | 15 + 11（sarek-async） | WinterSnowfall 的 DXVK 分支，面向老 GPU / Mali，额外支持 D3D7/6/5 |
| arm64ec | 22 | Windows-on-ARM 的 ARM64EC 目标，Winlator 系必需 |
| dyasync | 3 + 3 | 另一条异步构建 |

**panvk 不是 DXVK 的分支**：`isygold/panDXVK` 是独立项目，专适配 Mali 的 PanVK，
在驱动区登记，不要在组件区找。

其他类型的主要变体：VKD3D（proton / arm64ec / gamesir / tilting）、
Proton（arm64ec 35 / base 19，含 DroidDeck-Proton、coffincolors 等构建）、
Wine（base 55 / arm64ec-proton 10）、FEXCore（按月编号 2505…2611，另 nightly / stable）、
D7VK（base 11 / arm64ec 11 / nightly 1）。

## 三、运行库：全是人工补录，不跟上游

`components.json` 共 124 条（122 ready / 2 pending-manual），provider 以 Microsoft 为主（98 条）。

- **230 个下载链接全部指向** `github.com/The412Banner/winlator-contents/releases`（转存镜像）。
- 只有 5 个 `download.microsoft.com`、3 个 `download.visualstudio.microsoft.com` 是官方域名。
- App 另硬编码 6 条微软直链（.NET 9、VC++ 2026 x64/x86、.NET Framework 4.8.1、WMV9 等），
  版本号写死，换版本要改代码发版。

**结论：0 条运行库跟上游仓库自动更新。** 全部是清单式人工补录 + 镜像托管，
上游只在"溯源链接"这一层出现（`RuntimeLibrary.kt` 的 `RuntimeSource.lookup`）。

## 四、维护动作

- 组件源缺类型 → 改 `scripts/refresh-news.py` 的 `OFFICIAL_SOURCES`，`type` 必须与本文第一节的类型名逐字一致。
- 组件清单地址变了 → 改 `SourceCatalog.kt` 的 `componentSources`。
- 运行库要换版本 → 改 `winlator-contents` 上游清单，或在 `RuntimeLibraryRepository.kt` 的 `officialEntries` 里加直链。
- 驱动仓登记 → 改 `driver-registry.json`。
