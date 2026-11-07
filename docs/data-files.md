# 数据文件说明

仓库根目录的 JSON 分两类，别再把它们当垃圾数据删：

| 文件 | 谁读 | 说明 |
| --- | --- | --- |
| `news-data.json` | App `NewsRepository` | 新闻，CI 每日两次刷新 |
| `driver-registry.json` | App `DriverRegistryRepository` | 驱动源注册表 |
| `emulator-registry.json` | App `EmulatorRegistry` | 模拟器全量清单（9MB） |
| `emulator-ranking.json` | App `EmulatorRegistry` | 榜单与 insights（2MB） |
| `ranking.json` + `ranking-history/` | `scripts/gen_ranking_board.py` | 网页榜单源，App 不读 |
| `download-source-audit.json` | 人工 | 下载源分级审计报告 |
| `schema-audit.json` | 人工 | 分类体系审计报告 |
| `emulator-retired.json` | 人工 | 已退役模拟器记录 |
| `sources.json` | App `Accelerator` | 加速节点源清单 |

已删除：`accelerator-nodes.json`（与 `Accelerator.BUILTIN_NODES` 硬编码内容重复，
代码是权威来源，JSON 副本只会漂移）。
