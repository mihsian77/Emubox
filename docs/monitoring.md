# 监控范围（信源清单）

> 这份文档是**唯一事实来源**：代码里加了新信源、删了旧信源，都要同步改这里。
> 最后实测：2026-11-08，在 GitHub Actions runner 上逐条跑过（`scripts/probe_intl_sources.py`）。

## 一、结论先说

| 链路 | 能不能拿到数据 | 依据 |
| --- | --- | --- |
| YouTube（4 个频道） | **能**，每个 15 条 | 频道页 200，但 `oembed` 一律 404；channelId 写死后 `feeds/videos.xml` 稳定 |
| Reddit（3 个板块） | **能**，每个 25 条 | `new.json` / `old.reddit` 对机房 IP 一律 **403**；只有 `www.reddit.com/r/<sub>/new/.rss` 通，且会 **429**，需 8 秒间隔 |
| B 站（6 组搜索词） | **能**，每组 20 条 | `wbi/search/type?order=pubdate` 返回 code=0；泛词需过标题白名单 |
| Telegram | 页面能读，**未接入** | `t.me/s/<channel>` 200；缺的是「频道清单」，不是技术 |
| X / Twitter | **不能** | 公开页 403，无登录态拿不到 |
| 酷安 | **不能** | `api.coolapk.com` 返回 567（需设备签名），不逆向了 |
| 机核 / 少数派 | 能读 RSS，**未接入** | 通，但内容是泛科技，与模拟器生态重合度低 |

## 二、境外信源

### YouTube —— 4 个频道，每日 05:40

| 频道 | channelId | 定位 |
| --- | --- | --- |
| MrSujano | `UCu-NRUdNtfcjdGA5Abt3JUw` | 每日模拟器新闻，PC/安卓/掌机，最贴本项目 |
| ETA PRIME | `UC_0CVCfC_3iuHqmyClu59Uw` | 安卓设备跑 PC 游戏、Winlator 实机演示 |
| Retro Game Corps | `UC3IYhdamzjvEJ9CwkQa4RAw` | 掌机与模拟器教程、评测 |
| TechDweeb | `UCgRaK4A7yi4ZELCLUjdP_pg` | 复古游戏与模拟器工具 |

取数方式：`https://www.youtube.com/feeds/videos.xml?channel_id=<id>`（Atom，每频道 15 条）。
**不要**再走 `oembed` 或「抓频道页正则」——2026-11-08 实测 oembed 4/4 全 404，抓页面只有 1/4 命中。

### Reddit —— 3 个板块，每日 05:40

| 板块 | 定位 |
| --- | --- |
| r/EmulationOnAndroid | 安卓模拟器主板块，项目首发常在这里 |
| r/Winlator | Winlator 生态 |
| r/emulation | 综合，量大但杂 |

取数方式：`https://www.reddit.com/r/<sub>/new/.rss?limit=25`。
- 会被 429：三个板块必须间隔 8 秒，遇 429 退避 30 秒重试（脚本已实现）
- RSS **没有分数和评论数**（那是 JSON 字段，而 JSON 被 403）。目前 `score` 留空，`comments` 从正文正则里抠，抠不到就是 null——不做假数据。
- 想要完整字段只有一条路：注册 Reddit OAuth App（免费，需填用途），走 `oauth.reddit.com`。**未做，待定。**

## 三、国内信源

现状很薄，只有两处：

| 平台 | 接口 | 频率 | 落点 |
| --- | --- | --- | --- |
| IT 之家 | `m.ithome.com/api/news/newslistpageget`（官方分页 JSON） | 每日 10:30 / 22:30 | `news-data.json`（62 条 emulator / 52 条设备等） |
| B 站 | `api.bilibili.com/x/web-interface/wbi/search/type` | 每日 05:40 | `data/intl-news/<date>.json` 的 `bilibili` 字段 |

B 站搜索词（6 组）与过滤规则：

- 关键词：`Winlator`、`yuzu 安卓`、`switch 模拟器 安卓`、`ps2 模拟器 安卓`、`安卓 模拟器 手机`、`turnip 驱动`
- 排序 `order=pubdate`（综合排序会捞出几年前的爆款教程）
- 标题必须命中 `TITLE_MUST`（模拟器 / emulator / winlator / yuzu / 驱动 / vulkan …）
- 标题命中 `TITLE_DENY` 即丢（雷电模拟器 / 夜神 / MuMu / BlueStacks / 多开 —— 这些是 PC 上跑安卓，方向相反）

**明确没有接入的国内平台**：酷安（接口需设备签名，567）、贴吧（无公开接口且反爬强）、微博（需登录）、知乎（需登录）、微信公众号（无公开检索）、机核 / 少数派（RSS 可读但与本项目重合度低）。

## 四、GitHub 生态（不依赖第三方站）

| 用途 | 方式 | 频率 |
| --- | --- | --- |
| 新项目发现 | GitHub Search API，18 组查询（含中文：`模拟器` / `视觉小说` / `galgame` / `winlator` in:name,description） | 每周一 09:00，产物进 `drafts/` 走 PR 人审 |
| 版本与发布 | `api.github.com/repos/<repo>/releases` | 每日 02:40 |
| 下载量 | release 的 `download_count` 累加 | 每周一 03:20 |
| 驱动 / 组件 | 各源仓库 releases + 手写 `catalog/` | 每日 02:40 |

## 五、第三方榜单站（只作热度层）

| 站点 | 提供 | 状态 |
| --- | --- | --- |
| `the412banner/emulator-ranking`（GitHub Pages） | 榜单、star、14 天趋势、访问量、入门配方 | 每日快照 |
| `t3st31.github.io/Ranking-Emulators-Download` | 补充条目、下载增量、recipes、兼容性报告 | 每日快照 |

两者都是**个人静态站，无 SLA**。它们不参与「是否收录」判定（registry 299 条是逐条审计的），图标也已完全不依赖（0 外链）。
定位应为「趋势 + 发现线索」，下载量以 GitHub 自己累加的为准。

## 六、定时任务一览

| 工作流 | cron (UTC) | 北京时间 | 状态 |
| --- | --- | --- | --- |
| `intl-news.yml` 境外与国内信源采集 | `40 21 * * *` | 每日 05:40 | 2026-11-08 修复后重新启用 |
| `news-refresh.yml` 新闻数据 | — | 每日 10:30 / 22:30 | 正常 |
| `data-refresh.yml` 合并刷新（榜单/驱动/EmuReady/跑分） | — | 每日 02:40 前后 | 正常 |
| `registry-discover.yml` 生态发现 | `0 1 * * 1` | 每周一 09:00 | **当前被手动禁用**，需重开 |
| `downloads-refresh.yml` 下载量 | `20 19 * * 0` | 每周一 03:20 | 刚加，尚未到点 |
| `ranking-snapshot.yml` 榜单快照 | cron 被注释 | — | **未启用** |

## 七、已知缺口

1. `data/intl-news/` 的产物**目前没有任何消费方**：不喂 App，也没有脚本拿它跟 registry 比对。「库外项目发现」这一环还差一个 `diff-intl-vs-registry.py`。
2. Telegram 只差频道清单：给几个候选频道名，就能用 `t.me/s/<channel>` 抓公开预览页验证更新频率与相关性。
3. Reddit 的分数 / 评论数要完整，得注册 OAuth App。
4. 国内缺视频以外的平台，且没有一个能覆盖「中文圈首发」的源——中文项目爆发主要还是在酷安和 B 站，前者技术上拿不到。
