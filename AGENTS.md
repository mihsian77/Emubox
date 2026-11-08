# 提交与 PR 规范

## 提交信息

- 标题格式 `type(scope): 祈使句`。type 取 `feat|fix|data|docs|chore|refactor|ci`；scope 取模块名，如 `registry`、`driver-registry`、`soc`、`ui`、`news`。
- 一句话说清改了什么，不超过 50 字。不做 `A + B + C` 式罗列。
- 一个提交只做一件事；数据刷新与代码改动分开提交。
- 只写结果，不写过程：不写排查心路、不写「顺手」「另外」「最大头」。

## PR

- 描述必须对得上 `git diff --stat`。写了什么就改了什么，没写的改动不许夹带。
- 涉及数据来源的改动，说明数据从哪来、授权状态如何。
- 讲为什么改、影响哪些界面，不复述代码。

## 仓库内容红线

- 不提交第三方网盘链接、提取码、整合包下载地址。
- 不提交任何个人的 UID、昵称、主页地址、Cookie、Token。
- 不提交依赖登录态才能抓取的数据。
- 抓取脚本不留在仓库。外部动态在本地工具里跑，结果人工确认后以中性描述入库。
- 社区动态（社媒、视频站、网盘）采集禁止进 `.github/workflows/` 与 `scripts/`，CI 由 `scripts/check-monitor-scope.py` 拦截。分工唯一事实来源是 `docs/monitoring.md`。

## 数据取舍边界

- 查到的东西先全量摆出来，再给建议。不替用户做取舍。
- 禁止以「价值低」「热度不够」「重复」「大概率没用」「不是官方」为由，自行把条目/候选/异常从结果里去掉。
- 认为某条不该收录，就把理由写在它旁边一起呈现，而不是不写它。
- 任何「已过滤」「已剔除」「只保留高价值」的结论，都必须能反查出被剔除对象的完整名单。
- 缺口、失效、冲突一律逐条列出并标明事实依据；不给结论式省略。

## 汇报规范

- 交付后必须附一段结论，不能只丢文档链接。用户不读文档也要拿到结果。
- 结论要写清四件事：查了什么、查到什么、哪里有问题、要他判定什么。
- 表格优先于段落。逐条罗列超过五条时用表格。
- 需要用户拍板的事单独成段，写明选项和各自代价，不要替他决定。

## 提交前检查（2026-11-04 新增）

- 改过 `emulator-registry.json` 后，必须跑 `bash scripts/rebuild_gaps.sh`，确认 validate 全通过、总表数字与条目数一致。
- 新增条目要同步检查三处：缺口总表、App 内置列表（`EmulatorRegistry.kt` 的 `builtInRegistry()`）、App 端图标/简介。
- 内置列表是两份来源的合并，容易搞混：`scripts/emu_builtin_block.txt` 由 `refresh-ranking.py` 从社区排行生成，只覆盖排行榜里的条目（当前 63 条），**不跟随 registry**；`builtInRegistry()` 里还有约 70 条从 registry 精选的条目，只能手工加。新增条目若不在排行榜数据里，跑 `refresh-ranking.py` 不会带出来。
- 涉及「仓库打不开」的结论，必须区分 404 与 451（DMCA），并记录封锁时间；不得写成「项目不存在」。
- 官网判定要单独验：域名解析 + HTTPS + 页面标题，停放页/空页不算可访问。
- 交付前对照 `/workspace/项目库-下一步待办.md`，确认没有把待办当已完成。

## 收录流水线（2026-11-04 新增）

收录一个项目，**不允许从空白开始手填**。凡 GitHub 上能拿到的，必须由脚本先抓一遍。

```
discover_candidates.py   找项目（宽口径，宁可多抓，误判留给下一关）
        ↓  /workspace/生态发现/候选池-<日期>.json
batch_extract.py         候选 → 填好的记录（带证据与置信度）
        ↓  drafts/<日期>/drafts.json + report.md
人工只看两件事：标了「需人工确认」的、以及明显同名不同物的
        ↓
合并进 registry → validate-emulators.py → rebuild_gaps.sh
```

**三条硬规矩：**

1. **规则集中在 `scripts/infer.py`**。改判定逻辑只改这一个文件，不许在抓取脚本里再写一份。老数据迁移（`migrate_schema.py`）和新项目提取（`extract_entry.py`）用的是同一套函数，规则升级一次，两边同时生效。
2. **每条判定必须带证据**。`schema-audit.json` 里记 `method_evidence` 和 `hosts_evidence`（命中的资产名/关键词）。没有证据的判定视为无效。
3. **推不出就置空，不许猜**。`hosts` 为空标 `needs_review`，`method` 对终端/工具类置 `null`。空值会被校验放行、被人工看到；编一个看似合理的值会静默错下去。

**人工兜底只有一处**：`migrate_schema.py` 的 `HOST_OVERRIDES`。只允许写「仓库已 404/归档、资产拉不到」的条目，且必须在注释里写出处。

**已知边界（别当 bug 修）：**

- `sort=stars` 只能捞大项目，新项目靠 `NEW_QUERIES`（`sort=updated` + `created:>`）单独扫。两条通道都要留。
- 4★ 以下的新仓库任何排序都排不进前 30，只能靠人工投喂。
- 中文项目的 README 可能是 `README.zh-CN.md`，判断语种要查文件列表（`check_zh_readme.py`），不能读默认 README 数汉字。
