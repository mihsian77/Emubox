# 图标策略

## 结果（2026-11-08 全量跑完，302 条）

| 来源 | 条数 | 说明 |
| --- | --- | --- |
| **自托管图标** `icons/<id>.png` | **113** | 仓库内 icon 58 + APK 内 launcher 70，去重后 115 张，剔除 2 张灰圆后 113 |
| 社区站徽标 / 官网图标 | 29 | 保留：这类是按项目做的 logo，不是头像 |
| 空 → 字母牌 | 160 | 53%，明确表示「没有官方图标」，不做假 |

分布：113 张自托管 + 29 张外链（社区徽标/官网）+ 160 张字母牌，**0 张作者头像**。

自动过滤掉了 15 张（13 张自动 + 2 张人工复查发现同组灰圆）：8 张是「单色填充」（`vita3k`、`rpcsx`、`dusklight`、`citron`
这类透明底白图标，在白底上等于空白），3 张是纯灰圆环占位（`azahar` 系），
2 张是 1280px 的宣传图。判据写在 `scripts/fetch_icons.py` 的后处理里。

## 原始现状（改造前）

| 来源 | 条数 | 说明 |
| --- | --- | --- |
| 作者 GitHub 头像 | 128（121 + 7 条 `avatars.githubusercontent.com`） | **不是项目图标** |
| 社区站徽标（the412banner / t3st31） | 73 | 可用，但同一家族共用一张（Winlator 系 20 多条全是同一张图） |
| 仓库内图标 | 45 | 质量最好，多为 `logo.png` |
| 空 | 56 | 渲染成灰色首字母 |

问题不在「缺图」，在**用了错的图**：头像代表「谁做的」，图标代表「是什么」。
`weihuoya` 一个人做了 Citra / PPSSPP / Dolphin 三个分支，列表里三张一模一样的脸，
反而比没图更难认；Winlator 二十多个 fork 共用一张父项目徽标，等于没有区分度。

## 取图优先级

1. **APK 里的 launcher 图标** —— 用户装完看到的就是它，唯一「所见即所得」的来源
2. **仓库里的 `fastlane/metadata/android/<locale>/images/icon.png`** —— F-Droid 规范，512×512
3. **仓库里的 `res/mipmap-*/ic_launcher.png`** —— 取最高密度
4. 官网 `apple-touch-icon` / `og:image` —— 只对桌面项目兜底，命中率低且尺寸不齐
5. **字母牌（首字母 + 按名称散列的品牌色）** —— 明确表示「没有官方图标」，不做假

作者头像从链路里删掉了：它既不是 1~4，也不如 5 有辨识度。

## 两条自动化路径

`scripts/fetch_icons.py` 已实现，两种模式互补：

| 模式 | 做法 | 覆盖 | 实测 |
| --- | --- | --- | --- |
| `--probe` | 按约定路径 HEAD jsDelivr | 开源安卓工程 | 295 条有 repo 的命中 58 条（**19.7%**：mipmap 为主，fastlane 次之） |
| `--apk` | Range 读 zip 尾部目录 + 只拉图标本体 | 一切有 APK 直链的条目 | 10/10 抽到，12MB 包只拉 ~200KB |

APK 路径的关键点：不下载整包。zip 的中央目录在文件末尾，先取最后 128KB
定位 `PK\x01\x02`，再对目标条目发一次 Range 请求，deflate 解压即可。
混淆包（资源名被改成 `res/as.png`）按「3~60KB + 正方形 + 96~512px」猜，
实测能抽到 Citra 的橙子、Termux 的终端、Winlator 的 Windows 标、EKA2L1 的鸭子。

**但 APK 路径不能全自动**：约三成会抽错（MeLonDS 抽到的是手柄贴图、j2meloader 抽到
白底白字的 JL）。所以流程必须是「机器抽 → `--sheet` 拼对照表 → 人眼过一遍 →
错的写进 `icons/overrides.json`」。

## 统一后处理

- 画布 512×512，居中，留 8% 安全边（安卓自适应图标会被裁圆角）
- 透明底转白底：白底白字的 logo（j2meloader）在深色卡片上会消失
- 输出 PNG（图标有平涂色块，PNG 比 WebP 小）
- 文件放 `icons/<id>.png`，registry 的 `logo_url` 指到
  `https://cdn.jsdelivr.net/gh/mihsian77/Emubox@main/icons/<id>.png`
  （jsDelivr 实测 3.5s 首回源、命中缓存后 <1s；仓库体积增加约 3~5MB）

## 已完成

- [x] 探测/抽取脚本 + 对照表
- [x] App 侧去掉作者头像兜底，改用字母牌（`ProjectAvatar` / `RepoAvatar`）
- [x] 全量跑 `--probe`（58 条入库）与 `--apk`（70 条入库）
- [x] 自动过滤单色/空壳/宣传图（13 张）
- [x] registry 的 `logo_url` 指向自托管地址，另压一份 144px WebP 进 APK assets（409KB）
- [x] 剩余 160 条走字母牌

## 还没做

- [ ] `icons/overrides.json` 的人工纠错通道（目前靠上游抽取质量，抽错的直接删）
- [ ] 新条目入库后自动跑一次 `--probe`（现在靠手动）
