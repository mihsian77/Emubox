# 参与 EmuBox

这是一个把安卓驱动、模拟器、运行库整理成一份可下载清单的工具。它能用的前提是**清单里的每一条都是真的**——版本号对、链接能下、分类没错。所以最有价值的贡献往往不是写代码，而是改对一条数据。

## 最有用的四种贡献

| 类型 | 举例 | 需要附带什么 |
|---|---|---|
| 数据纠错 | 某条目版本号过期、下载链接 404、分类放错 | 该项在清单里的 `id`，以及正确值的一手来源链接 |
| 新条目 | 作者发布了新模拟器 / 驱动，清单里没有 | 项目主页、发布页、是否仍在维护 |
| 界面问题 | 文字截断、深色模式下看不清、按钮点不动 | 机型、系统版本、截图 |
| 翻译 | 界面英文缺句、中文措辞别扭 | 截图或具体页面位置 |

**判断标准只有一条：能不能被第三方复核。** 写「我试了不行」没有用，写「这条的 `latest_tag` 是 v1.2.0，发布页实际已到 v1.4.1（链接）」才有用。

## 不接受的内容

以下内容会被直接关闭，不留讨论：

- 网盘链接、提取码、整合包、改版 APK 的分发地址
- 破解、去广告、绕过付费的补丁
- 需要登录才能拿到的东西（Cookie、私有 token、内部接口返回值）
- 任何个人的 UID、昵称、主页、Cookie、Token
- 「帮我加个 XX 游戏 / XX 模拟器」但给不出官方发布页的请求

原因不复杂：这个仓库只做**已知对象的元数据刷新**，不做外部内容的抓取与搬运。来源不清的东西一旦入库，没法验证，也没法下架。

## 提交代码

环境：JDK 17、Android SDK（`compileSdk 37`）。NDK 只在改 native Vulkan 采集时需要。

```bash
git clone https://github.com/mihsian77/Emubox.git
cd Emubox
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest   # 单元测试，含界面渲染快照
```

`app/libs/drivercheck.aar` 是驱动实测的内核，不在公开仓库里。CI 通过 `DRIVERCHECK_TOKEN` 从私有仓下载；本地编译缺这个文件时，涉及实测的功能会拿不到实现，其余部分正常。这不影响你改界面或数据。

改动 `emulator-registry.json` 之后必须跑：

```bash
python3 scripts/validate-emulators.py
bash scripts/rebuild_gaps.sh
```

校验不过的 PR 不会合。

## 提交信息

格式 `type(scope): 祈使句`，一句话说清改了什么。

```
fix(registry): 修正 Eden 的 latest_tag 到 v0.0.4
data(driver): 补三条 Turnip 8xx 构建
fix(ui): 深色模式下驱动卡片分数对比度
```

- `type` 取 `feat|fix|data|docs|chore|refactor|ci`，`scope` 取模块名（`registry`、`driver-registry`、`soc`、`ui`、`news`）
- 一个提交只做一件事，数据刷新与代码改动分开提交
- 正文最多 5 行，只写「改了什么 / 影响什么」，不写排查过程
- 不写「实测通过」「已核验」这类自证措辞——能不能跑由 CI 证明

完整规范见 [AGENTS.md](AGENTS.md)。

## Pull Request

- 描述要对得上 `git diff --stat`，写了什么就改了什么
- 涉及数据来源的改动，说明数据从哪来、授权状态如何
- 一次 PR 不要混进无关的格式化改动，否则没法审
- CI 会跑 i18n 校验、Lint、Debug/Release 编译和单元测试，四项全绿才会看

## 关于数据来源

清单里的星标数、发版信息、下载量来自各项目的公开页面与 GitHub API；跑分数据来自公开榜单；新闻条目来自公开资讯站点。

如果你是这个仓库引用的某个项目的作者，不希望它以某种方式被收录（描述不准确、图标不合适、希望下架），开一个 issue 说明条目 `id` 和你的要求即可，会按你的意思改或删。

## 项目文案怎么写

`emulator-registry.json` 里每条有三个文案字段，职责不同，不要混用：

| 字段 | 谁写 | 给谁看 | 规则 |
|---|---|---|---|
| `description` | 我们 | 用户（卡片与详情页的第一句话） | 一句话说清「这是什么、跑什么」，≤60 字 |
| `summary` | 脚本抓取 | 内部 | 上游 README 首段原文，只作翻译底稿与溯源凭证，不直接展示 |
| `note` | 我们 | 用户（卡片红字） | 只在需要解释「为什么这里没有下载按钮」时写，一句话说完 |

`description` 的硬性要求：

1. **只写事实**：平台、机种、技术路线。不写评价（「强大」「最佳」「稳定」）。
2. **不写可变数字**：星数、下载量、版本号、发布日期都不进文案——这些由字段承载，每天在变。
3. **不写谱系考据**：除非分支关系直接影响用户选择（如「X 的分支」），否则不写 fork 来源、改名史、社区归属。
4. **不写内部备注**：「待核」「直链」「实测」「本站」「原记录有误」这类属于审核过程，进 commit message，不进数据。
5. **不写停更状态**：由 `dead` / `dead_reason` 字段表达，界面自己会渲染徽章。
6. **中文优先**：专有名词保留原文（Vulkan、Adreno、Switch、Winlator），其余用通行中文译名。

`note` 只允许三句式：下载渠道说明（「安卓版经 Google Play 分发。」）、使用前提（「需自备游戏原版文件。」）、平台限制（「仅提供 Quest 头显的 APK。」）。审核笔记、决策记录、交叉比对结论一律不写。

改文案后跑 `python3 scripts/build_index.py` 重建索引，`emulator-offline.json` 会同步更新。

## 许可

代码 MIT。提交的贡献按同一许可授权。

引用的第三方数据、图标、名称归各自所有者，见 [NOTICE.md](NOTICE.md)。
