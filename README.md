# EmuHub 国内版

> 安卓驱动与模拟组件下载管理器 — 完整中文界面 + 国内下载加速 + UI 重做

基于 [NotZeetaa](https://github.com/NotZeetaa) 原创、[Rodrig02005](https://github.com/Rodrig02005) 维护的 [EmuHub-APP](https://github.com/Rodrig02005/EmuHub-APP)（MIT 协议）修改而来的国内增强版。

---

## ✨ 特性

### 📱 Android App（主力产品）

- **完整中文界面** — 288 条词条全部汉化，专业国内用语，无 AI 翻译腔
- **国内下载加速** — 内置 5 个公益加速节点，支持自动选最低延迟 / 手动切换 / 关闭，设置里实时测速显示延迟
- **国内版专属配色** — 深蓝 + 朱红主题，区别于原版
- **多语言支持** — 简体中文 / English / Português / Español / Français / Deutsch，可在设置内切换
- **驱动与组件管理** — Turnip / 高通 GPU 驱动、Wine / Proton、DXVK / VKD3D / D7VK、Box64 / FEXCore 等
- **硬件检测** — 自动识别 GPU 型号、Adreno 代际、安卓版本、运行内存
- **下载管理** — 暂停 / 继续 / 断点续传 / 下载库管理
- **组件指南** — 每个组件的作用、优先尝试、适用场景、切换时机

### 🌐 网页版（次选）

`web/` 目录下提供一个简化版网页，作为无法安装 App 时的备用方案：
- 中文界面，移动端适配
- 基础加速节点切换
- Turnip 驱动最新版本获取
- 常用组件下载链接

> ⚠️ 网页版功能有限，**推荐使用 Android App 获得完整体验**。

---

## 🚀 下载

从 [Releases](https://github.com/mihsian77/EmuHub-CN/releases) 页面下载最新 APK。

最低系统要求：Android 9.0（API 28）

---

## 🛠 构建

### 环境要求
- JDK 17
- Android Studio Hedgehog 或更高版本
- Android SDK（compileSdk 36, targetSdk 37）

### 编译步骤
```bash
git clone https://github.com/mihsian77/EmuHub-CN.git
cd EmuHub-CN
./gradlew assembleDebug
```
APK 输出路径：`app/build/outputs/apk/debug/`

### 运行 i18n 校验
```bash
python3 scripts/check-i18n.py
```
检查内容：翻译完整性（漏译/多译）、占位符一致性、空翻译、Kotlin 硬编码扫描。

---

## ⚙️ CI/CD

| Workflow | 触发 | 功能 |
|----------|------|------|
| `ci.yml` | push / PR 到 main | i18n 校验 → Android Lint → 编译 Debug APK |

CI 会自动：
1. 运行 `scripts/check-i18n.py` 检查翻译完整性
2. 运行 `./gradlew lint` 检查代码质量（含 MissingTranslation / ExtraTranslation）
3. 运行 `./gradlew assembleDebug` 验证编译通过
4. 上传 Lint 报告和 Debug APK 作为构建产物

---

## 📁 项目结构

```
EmuHub-CN/
├── app/
│   └── src/main/
│       ├── java/com/emuhub/cn/
│       │   ├── Accelerator.kt        # 国内加速核心（节点管理 + 延迟测速 + URL重写）
│       │   ├── Screens.kt            # UI 界面（含加速设置 + 汉化署名）
│       │   ├── DownloadUtils.kt      # 下载逻辑（加速注入）
│       │   ├── Localization.kt       # 多语言框架
│       │   ├── SettingsManager.kt    # 设置存储
│       │   ├── NetworkUtils.kt       # 网络请求（加速注入）
│       │   ├── SourceCatalog.kt      # 资源目录（加速注入）
│       │   └── ui/theme/             # 主题（含国内版配色）
│       └── res/
│           ├── values/                # 英文（默认）
│           ├── values-zh-rCN/        # 简体中文
│           ├── values-de/             # 德语
│           ├── values-es/             # 西班牙语
│           ├── values-fr/             # 法语
│           └── values-pt*/           # 葡萄牙语
├── scripts/
│   └── check-i18n.py                 # i18n 校验脚本
├── web/
│   └── index.html                     # 简化版网页（次选）
├── .github/workflows/
│   └── ci.yml                         # CI 构建与校验
└── README.md
```

---

## ⚡ 国内加速说明

App 内所有 GitHub 资源下载（驱动文件、组件、资源目录、API 请求）均会经过加速节点。

内置节点：
| 节点 | 域名 | 说明 |
|------|------|------|
| gh-proxy 官方 | gh-proxy.com | 主力节点，日调用量百万级 |
| ghfast 多线 | ghfast.top | 多线 CDN |
| ghproxy 镜像 | mirror.ghproxy.com | 老牌镜像 |
| moeyy 公益 | github.moeyy.xyz | 国内公益 |
| llkk 公益 | gh.llkk.cc | 国内公益 |
| 直连 | github.com | 不加速 |

加速模式：
- **自动**：测试所有节点延迟，自动选择最低延迟的节点
- **手动**：用户自行选择节点
- **关闭**：直连 GitHub

---

## 📜 开源协议

MIT License

- 原作者：[NotZeetaa](https://github.com/NotZeetaa)
- 维护者：[Rodrig02005](https://github.com/Rodrig02005)
- 国内版修改：[moon279](https://github.com/mihsian77)

所有驱动与软件版权归原作者所有。本项目仅提供下载管理与汉化加速功能。

---

## 🙏 致谢

- NotZeetaa — EmuHub 原创作者
- Rodrig02005 — EmuHub-APP 维护者
- 各公益 GitHub 加速节点运营者
- Mesa / Turnip / DXVK / Wine / Box64 等开源项目开发者
