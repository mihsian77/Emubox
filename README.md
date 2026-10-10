# EmuHub 国内版

> 安卓驱动与模拟组件下载管理器 — 完整中文界面 + 国内下载加速 + UI 重做

基于 [NotZeetaa](https://github.com/NotZeetaa) 原创、[Rodrig02005](https://github.com/Rodrig02005) 维护的 [EmuHub-APP](https://github.com/Rodrig02005/EmuHub-APP)（MIT 协议）修改而来的国内增强版。

---

> 🌐 **GitHub 下载 APK 限速？不想装 App？** [点这里使用网页版备选](https://mihsian77.github.io/EmuHub/) — 浏览器直接下载驱动与组件，内置国内加速。

---

## ✨ 特性

### 📱 Android App（主力产品）

- **中文界面** — 界面和设置汉化
- **国内下载加速** — 接入 [MirrorHub](https://github.com/mihsian77/MirrorHub) 自动维护的节点库（每6小时测速更新），支持自动选最低延迟 / 手动切换 / 关闭，设置里实时测速显示延迟
- **国内版专属配色** — 深蓝 + 朱红主题，区别于原版
- **中英双语** — 简体中文 / English，可在设置内切换
- **驱动与组件管理** — Turnip / 高通 GPU 驱动、Wine / Proton、DXVK / VKD3D / D7VK、Box64 / FEXCore 等
- **硬件检测** — 自动识别 GPU 型号、Adreno 代际、安卓版本、运行内存
- **下载管理** — 暂停 / 继续 / 断点续传 / 下载库管理
- **组件指南** — 每个组件的作用、优先尝试、适用场景、切换时机

### 🌐 网页版（次选）

独立网页版作为无法安装 App 时的备用方案：
- 在线地址：**https://mihsian77.github.io/EmuHub/**
- 仓库：[mihsian77/EmuHub](https://github.com/mihsian77/EmuHub)（已归档，稳定备选）
- 中文界面，移动端适配，内置国内加速节点
- 可直接在浏览器下载 Turnip 驱动、Wine、DXVK 等常用组件

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
├── .github/workflows/
│   └── ci.yml                         # CI 构建与校验
└── README.md
```

---

## 📦 驱动来源说明

App 内置驱动注册表，维护 35 个驱动源，覆盖三类 GPU：

- **高通 Adreno Turnip**（18 个）：通用系列（6xx/7xx/8xx）+ 8xx 专属（810/812/829/830/840）+ 7xx 专属
- **ARM Mali PanVK**（16 个）：Bifrost（G52/G72/G76）、Valhall v9/JM（G57/G77/G99）、Valhall v10/CSF（G610/G615/G710/G720）
- **紫光展锐 Xclipse RADV**（1 个）

**分类维度**：按 GPU 厂商、架构系列、具体型号、目标平台（通用 / winlator / eden / termux）、驱动类型（Mesa 上游构建 / 定制补丁 / 厂商固件提取）、成熟度（stable / beta / ci / alpha / experimental）。

**包格式**：所有驱动包标准为 zip（adpkg），内含 `meta.json`（名称/版本/厂商/最低 API/驱动库名）+ 驱动本体 `.so`。下载管理页可对已下载驱动包执行"查看包信息"，直接解析 meta.json 展示详情。

**推荐逻辑**：启动时自动识别设备 GPU 型号与架构，按匹配度排序推荐（型号精确匹配优先，架构兼容次之，非本机型号降权不排除）；Mali 驱动强制匹配 frontend（JM/CSF）；Termux 专用源默认不推荐。可在设置中关闭"按设备匹配"查看全部驱动。

> 为防止驱动源被滥用，此处不列出具体仓库链接。驱动注册表数据随 App 版本更新，来源均为公开开源社区。

---

## ⚡ 国内加速说明

App 内所有 GitHub 资源下载（驱动文件、组件、资源目录、API 请求）均会经过加速节点。

**节点来源：[MirrorHub](https://github.com/mihsian77/MirrorHub)**（MIT 协议）— 自动维护的 GitHub 加速节点库，每 6 小时全量测速，自动清理失效节点，按延迟排序。App 启动时远程拉取最新在线节点列表，内置一批节点作为远程拉取失败时的兜底。

加速模式：
- **自动**：测试所有节点延迟，自动选择最低延迟的节点
- **手动**：用户自行选择节点
- **关闭**：不加速（直连 GitHub）

> 📌 二次分发声明：本应用加速节点数据由 MirrorHub 提供，遵守 MIT 协议。如二次分发或修改，请保留 MirrorHub 来源声明。

---

## 📜 开源协议

MIT License

- 原作者：[NotZeetaa](https://github.com/NotZeetaa)
- 维护者：[Rodrig02005](https://github.com/Rodrig02005)
- 国内版修改：[moon279](https://github.com/mihsian77)
- 加速节点数据：[MirrorHub](https://github.com/mihsian77/MirrorHub)（MIT 协议，自动维护节点库）

所有驱动与软件版权归原作者所有。本项目仅提供下载管理与汉化加速功能。

**二次分发要求**：如基于本项目二次分发或修改，请保留上述所有来源声明（含 MirrorHub 节点数据来源），遵守 MIT 协议。

---

## 💜 赞助

如果本项目帮到了你，欢迎通过[爱发电](https://afdian.com/a/moon279)支持 moon279 的维护工作。项目基于上游开源成果构建，赞助仅用于服务器成本、测试设备与维护时间投入。

---

## 🙏 致谢

- NotZeetaa — EmuHub 原创作者
- Rodrig02005 — EmuHub-APP 维护者
- [MirrorHub](https://github.com/mihsian77/MirrorHub) — 自动维护的 GitHub 加速节点库
- 各公益 GitHub 加速节点运营者
- Mesa / Turnip / DXVK / Wine / Box64 等开源项目开发者
