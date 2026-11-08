# 监控范围（唯一事实来源）

> 改信源先改这里，再改代码。CI 里 `scripts/check-monitor-scope.py` 会挡住违反分工的提交。
> 最近核对：2026-11-08（本地监控改为单入口 `run_all.py`，Telegram 接入，自动收录下线）。

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

### 生产（3 条）

| 任务 | 抓什么 | 地区 | 北京时间 | 落点 |
|---|---|---|---|---|
| `data-refresh.yml` 每日数据刷新（合并提交） | ① 榜单快照（两站数据自算）② 驱动/模拟器元数据（GitHub API）③ EmuReady 兼容性目录 ④ 跑分（安兔兔、鲁大师） | ②③ 国外 / ④ 国内 | 每天 02:40 | `emulator-ranking.json`、`driver-registry.json`、`bench-data/` |
| `news-refresh.yml` 每日新闻 | IT之家分页 JSON（国内）＋ 8 类组件上游 release + 8 个驱动源 release（国外）＋ WineHQ 源码目录 / mono（国外） | **国内 + 国外混在一条** | 每天 10:30、22:30 | `news-data.json` |
| `downloads-refresh.yml` 下载量补齐 | GitHub release 的 `download_count` 累加 | 国外 | 每周一 03:20 | `emulator-ranking.json` |

提交形态：三条都会 `git commit` 后直推 main，约 **3 条/天**，提交信息固定为
`chore(data): 每日刷新 <日期>` / `chore: 刷新新闻数据` / `chore(downloads): 每周刷新 release 下载量`。
这些提交是 App 的取数通道（App 读 main 分支 raw），不是无用记录；要压缩只能改 App 的取数分支，得随版本发。

### 校验（2 条，不采集）

`ci.yml`（构建 + Lint + schema + 监控分工）、`ci-data.yml`（数据文件一致性）。每次提交都跑。

### 手动触发（4 条，cron 已注释）

`ranking-snapshot.yml`、`registry-refresh.yml`、`refresh-bench-scores.yml`、`emuready-refresh.yml`
能力已并进 `data-refresh.yml`，保留文件只为手动单跑。

### 已删除（2026-11-08）

`registry-discover.yml` + `scripts/discover_candidates.py` + `scripts/batch_extract.py` + `drafts/`
全部删除。自动收录的口径没定稿，先下线，等"收哪些类型、按什么筛"定下来再重建（见 `AGENTS.md`）。

## 三、本地监控（本机 09:00，8 步）

**入口只有一个：`python3 run_all.py`。** 定时任务只执行这一条命令并把 stdout 原样贴出，
不再让模型逐步判断——之前"每次都空白"就是长 prompt 中途跑偏导致的。

产物在仓库外（`/workspace/社区监控/local-monitor/`），`reports/` 存日报，`state/` 存增量指纹，`logs/` 存原始输出。

| 步骤 | 脚本 | 看什么 | 地区 | 频率 |
|---|---|---|---|---|
| 1 | `collect_telegram.py` | Telegram · WinDroid Emulation：新项目/新版本/驱动构建 | 国外 | 每天 |
| 2 | `collect_gbatemp.py` | GBAtemp Scene News RSS，主机自制与模拟新闻 | 国外 | 每天 |
| 3 | `collect_bili.py` | B 站 5 个 UP（dreamboyn81、非梦nanjing、妖妖的夜、知喵~、麦克人）的动态与投稿，A/B/C 分级 | 国内 | 每天 |
| 4 | `collect_gitee.py` | Gitee 索引页的网盘分享号增删、密码变更 | 国内 | 每天 |
| 5 | `catalog_full.py` | 123 云盘 49 个分享的全量目录树 + 与上版 diff | 国内 | 每天 |
| 6 | `sync_registry_index.py` | 库索引（只留 id/name/repo/stars/platforms）供库外比对 | 内部 | 每天 |
| 7 | `collect_retroreplay.py` | Retro Replay 周报，用于发现并纠正假消息 | 国外 | 仅周日 |
| 8 | `discover_emucr.py` | EmuCR 公告 → 抽 GitHub 地址 → 找库外项目 | 国外 | 仅周一 |

`discover_drivers.py`（驱动发现）仍未挂进定时，只能手动跑，属待办。

### Telegram 实测（2026-11-08）

- 通道：`tg.i-c-a.su/rss/<channel>`，`t.me` 本机直连 000 不可达；镜像单次上限 100 条
  （`?limit=200` 会被截回 100），当前约覆盖 12 天。
- 样本：100 条 / 12 天，日均 **8.3 条**，峰值 **17 条**（11-08 当日，未满一天）。
- 可用率：**69/100**（A 级 60、B 级 9）。剩下 31 条是成品包（mediafire / modsfire / gofile /
  apkvision / romsfun）或纯图片，一律丢弃，不转载、不入库。
- 库外比对：45 个去重仓库里 **25 个不在主库**（GTA V 移植、WWE 2K14 重编译、Eglantine、
  BananaNative、Lichii、Xenra、Vita3K+、MaliBridge 等）。

## 四、按国内 / 国外清点

**国内 5 条**

| 信源 | 跑在哪 | 产出 |
|---|---|---|
| IT之家 | 仓库 | 手机/安卓/鸿蒙/游戏电竞等栏目的资讯条目 |
| 安兔兔 + 鲁大师 | 仓库 | 跑分与 SoC 参数 |
| B 站 5 个 UP | 本地 | 新动态、新投稿、候选项目 |
| Gitee 索引页 | 本地 | 网盘分享号增删 |
| 123 云盘 | 本地 | 目录树变化（新分区、新文件） |

**国外 7 条**

| 信源 | 跑在哪 | 产出 |
|---|---|---|
| GitHub API | 仓库 | 驱动/模拟器元数据、releases、下载量 |
| EmuReady | 仓库 | 兼容性目录（哪个游戏在哪个模拟器上能跑） |
| 组件上游（DXVK/VKD3D/D7VK/FEX/Box64/WOWBox64/Proton/GE-Proton/Wine） | 仓库 | 版本号与更新日志 |
| 驱动源 8 个（K11MCH1 / StevenMXZ / Purple / faux123×2 / xodiosx / adesyaefull / panDXVK / RADV） | 仓库 | 驱动版本与更新日志 |
| WinDroid（Telegram） | 本地 | 新项目、新版本、Mali/PanVK 构建 |
| GBAtemp | 本地 | 每日场景新闻 |
| Retro Replay | 本地 | 每周发版汇总 |
| EmuCR | 本地 | 项目发现（100 篇里 88 篇带 GitHub 地址） |

## 五、明确不做的（附实测结论）

| 平台 | 结论 | 依据 |
|---|---|---|
| **YouTube** | 不接 | 能取（runner 上 4 频道各 15 条），但属社区动态，进仓库违反边界；本机不可达。2026-11-08 已删除全部脚本与缓存 |
| **Reddit** | 不接 | 同上。`r/<sub>/new/.rss` 3 板块各 25 条，`new.json` 一律 403。2026-11-08 已删除全部脚本与缓存 |
| X / Twitter | 不能 | 公开页 403，无登录态拿不到 |
| 酷安 | 不能 | `api.coolapk.com` 需设备签名（567 / 403） |
| 贴吧 | 不能 | 无公开接口，反爬 403 |
| 微博 / 小红书 / 抖音 | 不能 | 需登录态或签名（x-s、a_bogus） |
| 微信公众号 | 不划算 | 搜狗入口可用，但结果是营销号搬运，比 B 站慢 1–2 天 |
| Wololo | 不用 | 更新频率与内容量已退化 |

## 六、自动校验

`scripts/check-monitor-scope.py` 在 `ci.yml` 里跑，命中任意一条即失败：

- `.github/workflows/*.yml` 出现社媒/视频/网盘域名（youtube、reddit、t.me、x.com、bilibili、weibo、coolapk、douyin、xiaohongshu、tieba、123pan 等）
- `scripts/*.py` 出现上述域名采集逻辑（确需保留的加 `# monitor-scope: allow` 注释）
- 仓库里出现 `data/intl-news/` 目录

## 七、待定

1. **自动收录机制重设计**：先定"收哪些类型、按什么筛"，再重建发现通道。现阶段一律人工录入。
2. **提交量压缩**：3 条/天。要么新闻改一天一更（白天新闻最晚迟 12 小时），要么把数据挪到独立分支（要改 App）。
3. **`discover_drivers.py` 挂进定时**：现在只能手动跑。
