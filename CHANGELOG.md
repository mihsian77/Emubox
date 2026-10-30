# Changelog

版本规则：正式版 `x.y.0`（y = 功能批次，定稿发布）；预发布 `x.y.z`（z > 0，CI 自动发布的中间构建，带 Pre-release 标记）。本文件每个正式版一节，记录该批全部改动；预发布号不单独记录，归入下一正式版。

## [1.4.0] - 未发布

### Added
- 版本规则改为正式版/预发布双轨（正式版 x.y.0，预发布 x.y.z 由 CI 自动发布并置顶）
- 新增本 Changelog 作为功能-版本映射

## [1.3.6] - 2026-10-30

### Added
- EmuReady 兼容性评分卡接入设备页（按品牌+型号查社区兼容得分，未收录静默降级）
- EmuReady 社区数据卡接入模拟器详情弹窗（设备实测/SoC 同源数据入口）

### Fixed
- 设备页 Mali/Immortalis GPU 误标为 Adreno（从 renderer 提取厂商型号）
- wrapper 区卡片显示优化（ProjectAvatar + 版本徽标 + 译文切换）

### Changed
- 模拟器注册表同步 4 条 release 到最新（CapyPS5 / ConsoleFivePro / KytyPS5 / Brew2Droid）

## [1.3.5] - 2026-10-28

### Fixed
- 驱动检测超时与崩溃状态判定
- GPU 跑分数据可信度与 PC 对比口径修正
- 驱动专区仓库图标/简介截断/下载卡连接
- 来源切换弹窗更新时间内置化

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
