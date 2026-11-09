# Changelog

版本规则：正式版 `x.y.0`（y = 功能批次，定稿发布）；预发布 `x.y.z`（z > 0，CI 自动发布的中间构建，带 Pre-release 标记）。本文件每个正式版一节，记录该批全部改动；预发布号不单独记录，归入下一正式版。

## [1.4.0] - 未发布

### Added
- 首屏拆包：`emulator-index.json`（374KB，卡片字段）+ `emulator-details-0..7.json`（详情分片），首屏流量 3269KB → 374KB（-89%）。进详情页只拉该条目所在那一片，不再整包等 3MB
- 项目简介贴近官方：`scripts/fetch_summaries.py` 从 README 首段提炼一句话（无 README 用仓库 About，再没有用发布说明），302 条里 294 条有摘要（readme 261 / about 30 / release 3）。卡片和详情页优先展示 `summary`，`description` 保留作者原文
- 模拟器页两级筛选：一级用途分区（主机模拟 200 / Windows 兼容 45 / 原生移植 33 / 前端工具 21 / 视觉小说 3），二级按模拟平台（PS2 / Switch / 3DS…，按条目数取前 18）。原来是一行 60+ 个分类 chips 横滑
- 字段命名统一为 snake_case（`logo_url` / `downloads_total` / `latest_tag` / `summary`），新增 `scripts/validate-schema.py` 上 CI，别名出现即失败
- 图标全量入库：113 张自托管到 `icons/<id>.png`（仓库内 58 + APK 内 70），剔除单色填充/空壳/灰圆/宣传图 15 张，其余 160 条走字母牌（另有 29 条社区/官网图标）；另压 144px WebP 进 APK assets（409KB），本地优先、远程兜底
- 新闻系统 v3：Wine 小版本接入（dl.winehq.org 目录源，补齐 10.19–11.20 中间版本）；组件/运行库分类口径统一（Wine / Box64 / Proton-GE 归组件，DXVK / VKD3D-Proton / Wine Mono / Wine Gecko / Mono 归运行库）；IT 之家正文抓取落库；同 url 去重防重复收录
- 新闻卡片弹窗化：卡片去除来源名，点击后应用内弹窗阅读全文（正文优先，无正文回退摘要）；官方条目支持翻译与查看原文
- 各分区新闻列表分页：3 条预览 + 「查看全部」近全屏列表（8 条/页）
- 驱动专区更新动态栏：release 标题 + 日志弹窗，英文自动翻译 + 查看原文
- EmuReady 深度接入：设备页兼容评分卡（品牌+型号 → 社区兼容得分）、模拟器详情弹窗社区报告入口、多关键词 + socName 兜底识别
- 平台标注：桌面模拟器在安卓端装不了，卡片标「仅桌面 · Windows/macOS/Linux」，多生态保留但不假装能装
- 图标取图流水线 `scripts/fetch_icons.py`：仓库约定路径探测 + APK 内 launcher 图标 Range 提取（不下载整包）+ 人工复核对照表，口径见 `docs/icons.md`
- 模拟器注册表治理：多平台源（GitLab / Gitea / NotABug / Gitee）release 拉取、图标补全、APK 直链修正、来源合规分级与失效标记、release 日志补录、123 盘直达卡片（网盘来源/路径/提取码/打开）
- wrapper 区：翻译重做（专名与社区术语保留原文，只译解释句）+ 译文/原文切换
- 版本双轨规则：正式版 x.y.0，预发布 x.y.z 由 CI 自动发布并置顶；本 Changelog 作为功能-版本映射
- 驱动检测支持驱动包依赖闭包：跨机型高通包的 `libgsl.so` / `libllvm-glnext.so` 按驱动自身目录解析，缺库时直接报出库名，不再是一句 `dlopen failed`

### Fixed
- 12 条桌面/主机构建被写进 `latest_apk_url`（DOSBox-X 的 zip、DOSBox-Staging 的 exe、MeloNX 的 ipa、Ryujinx 的 AppImage），安卓端会给出一个装不上的下载按钮。已移到 `download_url`，并加进 schema 校验
- 离线内置列表与 registry 脱节：Kotlin 里手写 130 条字面量，早已和 registry 不同步（有把 .png 当 APK 的条目、缺 summary）。改为读 `assets/emulator-offline.json`
- `channel` 脏值（`stable/unknown`、`alpha`、`nightly`、`canary`、`archive`）归一为 `stable` / `prerelease`
- 版本号停在旧正式版：刷新脚本优先挑「非预发布」那条，只发 nightly/beta 的项目永远拿不到新版本，发过预发布的项目在按日期排序里也会被排到后面。改为「最新一条就是当前版本」，`channel` 标 `prerelease`，当前版本只有源码包时回退到最近带 APK 的版本
- 图标用作者头像顶替：同一作者多个项目在列表里是同一张脸。改为「真图标 → 首字母字母牌（按名称散列配色）」，作者头像退出链路
- 只有 `download_url`、没有 homepage 的条目按钮变灰（DraStic / MYPS2），按钮回退链补上 `download_url`
- 下载稳定性系列（1.3.1–1.3.4 热修复）：416 续传死循环根治、下载完成判定兜底（解决"完成仍卡连接中/误报失败"）、死文件记录可删与卡死放行重下、失败原因可视化、捆绑包下载容错与结果汇总重试
- 设备页 Mali/Immortalis GPU 误标为 Adreno（从 renderer 提取厂商型号）
- 驱动检测超时与崩溃状态判定、GPU 跑分数据可信度与 PC 对比口径修正
- 驱动专区仓库图标/简介截断/下载卡连接、来源切换弹窗更新时间内置化
- 新闻管道：驱动资讯不再混入 IT 之家杂项，官方 release 条目优先保留（组件/运行库资讯防空）
- 新闻分类迁移重复收录（历史 component 条目与新抓 runtime 条目同 release 双 id）
- 文件权限失败无任何提示：启动跳系统授权页，用户直接返回后下载全部失败且查不到原因。改为全局提示条 + 返回未授权时说明对话框（去设置 / 改选文件夹 / 稍后三条出路），下载建文件失败时也按缺权限给出指引
- 下载不是前台服务：GB 级驱动切后台或锁屏被系统冻结，回来显示暂停。改为活动下载期间常驻 dataSync 前台服务（含进度通知），下载结束自动停止
- Vulkan 格式矩阵只读 32 位特性位（`vkGetPhysicalDeviceFormatProperties`）：1.3/1.4 的 `VK_FORMAT_FEATURE_2_*` 高位标志被截断，表现为「DXVK 要的特性显示不支持」。改用 `VkFormatProperties2` + `VkFormatProperties3` 采集 64 位值，驱动不支持时明确标注只含 32 位
- GitHub API 未认证限额 60 次/小时，冷启动并发拉多个驱动源会 403 且列表直接变空。加可选 Token（Keystore 加密、经代理自动剥离）+ 限流/无 release 两种空态说明
- DeepSeek Key 明文存 SharedPreferences。改为 Android Keystore AES/GCM 加密存储，旧明文启动时一次性迁移并删除
- FileProvider 暴露整个 `Android/data/<pkg>/files/`。收窄到 `files/Download/` 一层，驱动解压目录与测试中间产物不再可被外部读取
- 第三方驱动加载前无风险告知：首次进驱动/组件分区说明「驱动是原生代码，只装可信来源」
- 加速节点拼接保留 `http://` 前缀的原始 URL，拼出 `https://proxy/http://github.com/…` 必然 404。改为统一规范化为 https

### Changed
- 模拟器专区：分类折叠改为合并大分类切换（首行大分类，点击查看全列表与其他小分类），减少分类条目占用空间
- Release 列表整理：1.3.0 改回正式版，删除无意义的 1.3.5，正式版命名统一为 EmuHub-CN x.y.z

## [1.3.0] - 2026-10 中旬

### Added
- EmuReady 兼容性数据库接入（设备查询入口）
- 新闻数据管道（IT 之家每日刷新 + 离线兜底）与主页/模拟器资讯 UI
- 驱动更新提示（registry release 字段扩展 + 更新卡/徽标）
- wrapper 区（winlator-contents 清单 + release 区块）
- 设备页 SoC 知识库、跑分引擎（curated 数据 + PC 对比）
- 123 盘监控、bench 数据自动刷新管道
- UI 主题重构（璃光暗夜模板）

### Fixed
- 芯片知识数据准确性（骁龙 8 Gen 2 CPU 规格等）
- SoC 跑分引擎无数据、驱动检测列表过滤

## [1.2.0] - 2026-10 上旬

### Added
- 模拟器专区（t3st31/the412banner 双源合并、分类折叠、图标链路统一）
- 对比矩阵 + 版本 diff + 全局搜索
- 收藏/视图模式/发布时间
- SoC 知识库全量数据、驱动对比矩阵、下载库信息补全
- 数据源扩展（趋势/食谱/统计）与 Insights UI

### Fixed
- 模拟器专区深度修复（fork 条目恢复、空 windows 分类清理、下载缺失补齐）

## [1.1.0] - 2026-09 底

### Added
- 驱动专区（driver-registry.json 注册表 + 自动匹配引擎 + meta.json 解析）
- 组件专区（catalog 双源合并 + 来源排序 + 捆绑包）
- 运行库专区（官方直链层 + URL 替换表 + 免责声明）
- MirrorHub 加速层（多节点测速/自动选择/Code Search 发现）
- 驱动检测与自定义驱动加载（native Vulkan 采集 + AdrenoTools 集成）
- GPU/CPU 跑分（native 基准）

### Fixed
- 下载机制（断点续传/自动重试/多线程/416）
- 下载路径分类子文件夹 + 自定义目录

## [1.0.0] - 2026-09 中旬

### Added
- 包名迁移 com.emuhub.cn → com.emubox.app
- 应用名与图标替换、README 独立化
- 底部 5 Tab 导航重构、AGP/CMake/NDK 工程骨架
