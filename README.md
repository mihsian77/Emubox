# EmuBox

> 安卓 Windows 模拟环境的驱动、组件与运行库下载管理器。完整中文界面、国内下载加速、按设备自动匹配。

EmuBox 脱胎于 [Rodrig02005/EmuHub-APP](https://github.com/Rodrig02005/EmuHub-APP)（MIT 协议，作者 Rodrigo Castro），在其基础上重做了界面、资源目录与下载链路，面向国内 Winlator / Eden / GameHub 等模拟环境用户。

---

> GitHub 下载 APK 限速，或暂时不想安装 App？可使用[网页版备选](https://mihsian77.github.io/EmuHub/)，在浏览器内直接下载常用驱动与组件。网页版功能有限，完整体验请使用 App。

---

## 功能

- **驱动专区** — Turnip（高通 Adreno）、PanVK（ARM Mali）、三星 Xclipse 三类 GPU 驱动，内置 35 个驱动源，按 GPU 架构、型号、目标平台、成熟度分类
- **组件专区** — Wine / Proton、Box64 / FEXCore / WOWBox64、DXVK / VKD3D-Proton / D7VK 等图形与转译组件，聚合多个上游目录
- **运行库专区** — VC++ 运行库、.NET、解码器、字体、DirectX 等 Winlator 常用依赖，附中文用途说明与安装指引
- **按设备匹配** — 自动识别 GPU 型号、Adreno / Mali 代际、安卓版本与运行内存，驱动按匹配度排序推荐；可在设置关闭以查看全部
- **国内下载加速** — 接入 [MirrorHub](https://github.com/mihsian77/MirrorHub) 维护的加速节点，自动测速选优，也可手动切换或关闭
- **下载管理** — 暂停 / 继续 / 断点续传，按类型分目录存放，下载库可扫描本地已有文件、查看包内 `meta.json` 信息
- **组件指南** — 说明每个组件的作用、优先选择、适用场景与切换时机
- **中英双语** — 简体中文 / English，设置内切换

## 下载

从 [Releases](https://github.com/mihsian77/EmuHub-CN/releases) 获取最新 APK。最低系统要求 Android 9.0（API 28）。

## 构建

环境：JDK 17、Android SDK（compileSdk 37、NDK 用于 native Vulkan 采集）。

```bash
git clone https://github.com/mihsian77/EmuHub-CN.git
cd EmuHub-CN
./gradlew assembleDebug
```

APK 输出于 `app/build/outputs/apk/debug/`。

翻译完整性检查：

```bash
python3 scripts/check-i18n.py
```

检查漏译 / 多译、占位符一致性、空翻译与 Kotlin 硬编码。

## CI

push / PR 到 main 时，`ci.yml` 依次执行 i18n 校验、Android Lint、Debug 编译，并上传 Lint 报告与 APK。

## 项目结构

```
app/src/main/
├── java/com/emubox/app/
│   ├── Accelerator.kt        # 加速节点管理、延迟与带宽测速、URL 重写
│   ├── Screens.kt            # 各专区与设置界面
│   ├── SourceCatalog.kt      # 驱动与组件资源目录
│   ├── RuntimeLibrary.kt     # 运行库目录与中文说明
│   ├── DownloadUtils.kt      # 下载逻辑与文件命名
│   ├── NativeVulkanBridge.kt # Vulkan 采集 / 跑分 JNI 桥
│   ├── HardwareUtils.kt      # 设备硬件识别
│   └── ui/theme/             # 主题与配色
├── cpp/vulkan_collector.cpp  # native Vulkan 信息采集与基准
└── res/
    ├── values/               # 英文（默认）
    └── values-zh-rCN/        # 简体中文
```

## 驱动来源

内置驱动注册表维护 35 个源：

- **高通 Adreno（17）**：Turnip 构建，覆盖 6xx / 7xx 通用系列与 8xx（含 810 / 812 / 829 / 830 / 840）专属构建
- **ARM Mali（17）**：PanVK 构建，覆盖 Bifrost（G52 / G72）、Valhall v9 / JM（G57 / G77 / G99）、Valhall v10 / CSF（G610 / G615 / G710 / G720）
- **三星 Xclipse（1）**：基于 AMD RDNA 的 Xclipse GPU 驱动

驱动包标准格式为 zip（adpkg），内含 `meta.json`（名称、版本、厂商、最低 API、驱动库名）与驱动本体 `.so`。注册表随 App 版本更新，来源均为公开开源社区；为避免驱动源被收集滥用，此处不列具体仓库地址。

## 加速说明

App 内所有 GitHub 资源（驱动、组件、运行库、资源目录）的下载均可经加速节点转发。节点数据由 [MirrorHub](https://github.com/mihsian77/MirrorHub)（MIT）维护，定时测速、清理失效节点并按延迟排序；App 启动时拉取最新列表，并内置一批节点作为兜底。

加速模式：**自动**（测速后选最优节点）、**手动**（自行指定节点）、**关闭**（直连）。

二次分发或修改本项目时，请保留 MirrorHub 来源声明。加速节点仅做下载转发，节点本身不作为本项目的收费卖点。

## 开源协议

MIT License。

- 上游 [EmuHub-APP](https://github.com/Rodrig02005/EmuHub-APP)：Rodrigo Castro（Rodrig02005）
- 本项目修改与维护：moon279（mihsian77）
- 加速节点数据：[MirrorHub](https://github.com/mihsian77/MirrorHub)（MIT）

所有驱动、组件与软件的版权归各自作者所有，本项目仅提供下载管理、匹配与加速。二次分发请保留上述来源声明，遵守 MIT 协议。

## 赞助

项目建立在上游开源成果之上。如果它帮到了你，可在[爱发电](https://afdian.com/a/moon279)支持维护，所得用于测试设备与维护投入。

## 致谢

- Rodrigo Castro（Rodrig02005）— EmuHub-APP 作者
- NotZeetaa — EmuHub 原创作者
- MirrorHub 与各公益加速节点运营者
- Mesa / Turnip / PanVK / DXVK / Wine / Box64 等开源项目开发者
