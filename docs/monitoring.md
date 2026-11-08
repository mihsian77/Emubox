# 监控范围（唯一事实来源）

> 改信源先改这里，再改代码。CI 里 `scripts/check-monitor-scope.py` 会挡住违反分工的提交。
> 最近核对：2026-11-08。

## 一、分工原则（一句话）

**仓库只管「已知对象变没变」，本机只管「冒出了什么新东西」。**

| | 跑在哪 | 管什么 | 判错代价 |
|---|---|---|---|
| 元数据刷新 | GitHub Actions | 星标、发版、下载量、兼容性、跑分、新闻条目 | 下次刷新自动覆盖，可回滚 |
| 社区动态 | 本机 09:00 定时任务 | 谁发了什么、网盘新增了什么、可能是新项目的线索 | 每条都要人判断，判错会把噪音写进库 |

两条硬边界（写进 `AGENTS.md`）：

1. **能在本机跑的外部动态，一律不进仓库。** 抓取脚本不留在仓库，结论人工确认后以中性描述入库。
2. **不搬运第三方内容。** 别人的投稿、网盘目录、帖子正文只在本机留标题/文件名/时间/链接，不进 assets、不进 App、不发 PR。

## 二、仓库自动更新（GitHub Actions）

### 生产（4 条）

| 任务 | 抓什么 | 地区 | 北京时间 | 落点 |
|---|---|---|---|---|
| `data-refresh.yml` 每日数据刷新（合并提交） | ① 榜单快照（两站数据自算）② 驱动/模拟器元数据（GitHub API）③ EmuReady 兼容性目录（emuready.com）④ 跑分（安兔兔、鲁大师） | ②③国外 / ④国内 | 每天 02:40 | `emulator-ranking.json`、`driver-registry.json`、`bench-data/` |
| `news-refresh.yml` 每日新闻 | IT之家分页 JSON（国内）＋ GitHub releases（DXVK / VKD3D / Wine / Mesa，国外）＋ WineHQ / mono（国外） | **国内 + 国外混在一条** | 每天 10:30、22:30 | `news-data.json` |
| `downloads-refresh.yml` 下载量补齐 | GitHub release 的 `download_count` 累加 | 国外 | 每周一 03:20 | `emulator-ranking.json` |
| `ci.yml` / `ci-data.yml` 校验 | 不采集，只校验 schema 与构建 | — | 每次提交 | — |

### 已停用（1 条）

| 任务 | 状态 | 说明 |
|---|---|---|
| `registry-discover.yml` 生态发现 | **手动禁用**，总运行 0 次 | GitHub Search 只读，产物进 `drafts/` 走 PR 人审；要不要恢复见文末待定项 |

`ranking-snapshot.yml`、`registry-refresh.yml`、`refresh-bench-scores.yml`、`emuready-refresh.yml` 的 cron 已注释，能力并进 `data-refresh.yml`，保留只为手动触发。

## 三、本地监控（本机 09:00，8 步）

产物在仓库外（`/workspace/社区监控/local-monitor/` 或 `~/.emubox/local-monitor/`），`reports/` 存人读报告，`state/` 存增量指纹。

| 步骤 | 脚本 | 看什么 | 地区 | 频率 |
|---|---|---|---|---|
| 1 | `collect_bili.py` | B 站 5 个 UP（dreamboyn81、非梦nanjing、妖妖的夜、知喵~、麦克人）的动态与投稿，A/B/C 分级 | 国内 | 每天 |
| 2 | `collect_gbatemp.py` | GBAtemp Scene News RSS，主机自制与模拟新闻 | 国外 | 每天 |
| 3 | `collect_retroreplay.py` | Retro Replay 周报，用于发现并纠正假消息 | 国外 | 仅周日 |
| 4 | `collect_gitee.py` | Gitee 索引页的网盘分享号增删、密码变更 | 国内 | 每天 |
| 5 | `catalog_full.py` | 123 云盘 49 个分享的全量目录树 + 与上版 diff | 国内 | 每天 |
| 6 | `sync_registry_index.py` | 库索引（只留 id/name/repo/stars/platforms）供库外比对 | 内部 | 每天 |
| 7 | `discover_emucr.py` | EmuCR 公告 → 抽 GitHub 地址 → 找库外项目 | 国外 | 仅周一 |
| 8 | 汇报 | 汇总成 18 行，只报变化 | — | 每天 |

`discover_drivers.py`（驱动发现，8 组关键词搜 GitHub）尚未挂进定时，只能手动跑，属待办。

## 四、按国内 / 国外清点

**国内 5 条**

| 信源 | 跑在哪 | 产出 |
|---|---|---|
| IT之家 | 仓库 | 手机/安卓/鸿蒙/游戏电竞等栏目的资讯条目 |
| 安兔兔 + 鲁大师 | 仓库 | 跑分与 SoC 参数 |
| B 站 5 个 UP | 本地 | 新动态、新投稿、候选项目 |
| Gitee 索引页 | 本地 | 网盘分享号增删 |
| 123 云盘 | 本地 | 目录树变化（新分区、新文件） |

**国外 6 条**

| 信源 | 跑在哪 | 产出 |
|---|---|---|
| GitHub API | 仓库 | 驱动/模拟器元数据、releases、下载量 |
| EmuReady | 仓库 | 兼容性目录（哪个游戏在哪个模拟器上能跑） |
| 组件版本源（DXVK/VKD3D/Wine/Mesa/WineHQ/mono） | 仓库 | 版本号与更新日志 |
| GBAtemp | 本地 | 每日场景新闻 |
| Retro Replay | 本地 | 每周发版汇总 |
| EmuCR | 本地 | 项目发现（100 篇里 88 篇带 GitHub 地址） |

## 五、明确不做的（附实测结论）

| 平台 | 结论 | 依据 |
|---|---|---|
| **YouTube** | 技术可取，**不进仓库** | GitHub runner 上 4 个频道各 15 条（`feeds/videos.xml?channel_id=`）；本机国内出口不可达。按分工不放进 CI，见待定项 |
| **Reddit** | 技术可取，**不进仓库** | runner 上 `r/<sub>/new/.rss` 3 个板块各 25 条，8 秒间隔可避开 429；`new.json` 一律 403。本机不可达 |
| **Telegram** | 页面可读，**未接入** | `t.me/s/<channel>` 200；缺的是频道清单，不是技术 |
| X / Twitter | 不能 | 公开页 403，无登录态拿不到 |
| 酷安 | 不能 | `api.coolapk.com` 需设备签名（567 / 403） |
| 贴吧 | 不能 | 无公开接口，反爬 403 |
| 微博 / 小红书 / 抖音 | 不能 | 需登录态或签名（x-s、a_bogus） |
| 微信公众号 | 不划算 | 搜狗入口可用，但结果是营销号搬运，比 B 站慢 1–2 天 |
| Wololo | 不用 | 更新频率与内容量已退化 |

YouTube 与 Reddit 的结论是**分工结论**，不是技术结论：能取到，但这两条属于社区动态，进仓库就违反上面第 1 条边界。

## 六、自动校验

`scripts/check-monitor-scope.py` 在 `ci.yml` 里跑，命中任意一条即失败：

- `.github/workflows/*.yml` 出现社媒/视频/网盘域名（youtube、reddit、t.me、x.com、bilibili、weibo、coolapk、douyin、xiaohongshu、tieba、123pan 等）
- `scripts/*.py` 出现上述域名采集逻辑（确需保留的加 `# monitor-scope: allow` 注释）
- 仓库里出现 `data/intl-news/` 目录

## 七、待定（需要拍板）

1. **Telegram 频道清单**：给候选频道名，用 `t.me/s/<channel>` 验证更新频率与相关性后接进本地第 9 步。
2. **YouTube / Reddit 要不要进本地**：只有一条路——本机挂代理跑，不进仓库。你同意就按本地脚本实现，产物只留本机。
3. **`registry-discover.yml`**：恢复每周一跑 GitHub Search（只产候选不写库），还是删掉。
