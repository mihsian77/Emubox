# 图标策略

## 现状（2026-11-08 实测，302 条）

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
| `--probe` | 按约定路径 HEAD jsDelivr | 开源安卓工程 | 100 条中命中 22 条（~22%） |
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

## 待办

- [x] 探测/抽取脚本 + 对照表
- [x] App 侧去掉作者头像兜底，改用字母牌（`ProjectAvatar` / `RepoAvatar`）
- [ ] 全量跑 `--apk`（约 170 条有 APK 直链），人工过对照表
- [ ] `--probe` 命中项合并进 `icons/`
- [ ] 剩余仍无图标的，用字母牌，不再回退头像
