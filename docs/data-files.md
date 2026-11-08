# 数据文件说明

仓库根目录的 JSON 分三类，别再把它们当垃圾数据删：

## App 直接读

| 文件 | 谁读 | 说明 |
| --- | --- | --- |
| `emulator-index.json` | App `EmulatorRegistry` | **首屏索引**，374KB，302 条的卡片字段 + `detail_shard` |
| `emulator-details-0..7.json` | App `EmulatorRegistry` | 详情分片，每片 230~390KB，进详情页只拉一片 |
| `emulator-ranking.json` | App `EmulatorRegistry` | 榜单与 insights（2MB） |
| `news-data.json` | App `NewsRepository` | 新闻，CI 每日两次刷新 |
| `driver-registry.json` | App `DriverRegistryRepository` | 驱动源注册表 |
| `app/src/main/assets/source-profiles.json` | App `SourceProfiles` | 驱动/组件源的「给谁用 · 怎么装 · 注意」文案 |
| `sources.json` | App `Accelerator` | 加速节点源清单 |

`app/src/main/assets/emulator-offline.json` 是索引的离线副本（369KB），
首启无网时用它出首屏，不再在 Kotlin 里手写内置列表。

**为什么不直接用 `emulator-registry.json`**：3.2MB，其中 2243KB 是 `releases`
（每个版本 × 每个资产）。首屏只需要卡片上的二十来个字段，却要先把整包下完。
拆开后首屏流量 3269KB → 374KB（-89%）。三份文件必须同步生成，
CI 的 `index-check` 会在 registry 改了但索引没重建时直接失败。

## 全量源（不直接被 App 读）

| 文件 | 谁读 | 说明 |
| --- | --- | --- |
| `emulator-registry.json` | 构建脚本 | 全量清单（3.2MB），`build_index.py` 的输入 |
| `ranking.json` + `ranking-history/` | `scripts/gen_ranking_board.py` | 家族聚合榜，网页榜单源 |
| `emulator-retired.json` | 人工 | 已退役模拟器记录（含复核理由） |
| `catalog/source-profiles.json` | `build_source_profiles.py` | 驱动/组件说明的手写文案（可直接改，改完重跑脚本） |

## 审计报告（人工）

| 文件 | 说明 |
| --- | --- |
| `download-source-audit.json` | 下载源分级审计 |
| `schema-audit.json` | 分类体系审计 |

## 已删除

- `accelerator-nodes.json`：与 `Accelerator.BUILTIN_NODES` 硬编码内容重复，代码是权威来源
- `icons/resolved.json`：取图流水线的中间产物，`icons/*.png` 本身才是交付物

## 字段命名约定

一律 snake_case，别名不再接受（`emulator-registry.json` / `emulator-ranking.json`）：

| 现行 | 已废 |
| --- | --- |
| `logo_url` | `logoUrl` |
| `downloads_total` | `downloads` |
| `latest_tag` / `latest_date` | `latestTag` / `latestDate` |
| `summary` | `desc` |

`summary` 是 README 首段提炼的一句话（没有 README 时用仓库 About，再没有用发布说明），
`description` 保留作者原文。生成脚本：`scripts/fetch_summaries.py`。
校验脚本：`scripts/validate-schema.py`（CI 必过）。

## 来源门槛

`validate-schema.py` 会拦两类条目：

1. **网盘/搬运站**：`homepage` / `repo_url` / `download_url` / `latest_apk_url`
   出现 123pan、百度网盘、夸克、蓝奏、9k9k 等域名直接失败。
   这些只是「有人转存了一份」，不构成一手来源。
2. **停更却不写原因**：`dead: true` 必须带 `dead_reason`，否则详情页只剩一个灰名字。

历史上 40 多条只有 123 盘镜像的条目已逐条复核（见 `docs/download-source-audit.md`），
目前全库 0 条依赖网盘分发；仍在库里的「无下载」条目都是官方站/Play 分发或纯源码项目。
