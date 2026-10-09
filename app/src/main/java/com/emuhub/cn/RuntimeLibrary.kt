package com.emuhub.cn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Windows 运行库条目（来自 The412Banner/winlator-contents components.json，Bottles 步骤格式）。
 * 一个组件可能包含多个安装步骤（install_exe / archive_extract / copy_dll 等），
 * 下载时取第一个可下载文件（url 非空）作为主文件。
 */
data class RuntimeComponent(
    val name: String,
    val description: String,
    val provider: String,
    val category: RuntimeCategory,
    val status: String, // ready / needs-upstream / pending-manual
    val dependencies: List<String>,
    val steps: List<RuntimeStep>,
    val primaryFile: RuntimeFile?
) {
    val isReady: Boolean get() = status == "ready"
    val fileSize: Long get() = primaryFile?.size ?: 0L
    val checksum: String get() = primaryFile?.checksum.orEmpty()
    val downloadUrl: String get() = primaryFile?.url.orEmpty()
    val fileName: String get() = primaryFile?.rename ?: primaryFile?.fileName ?: name

    /** 中文用途说明：按 name 匹配常见组件，无匹配返回原英文 description */
    val chineseHint: String get() = RuntimeChineseHints.lookup(name) ?: description
}

/** 单个安装步骤 */
data class RuntimeStep(
    val action: String,
    val fileName: String,
    val url: String,
    val arguments: String,
    val checksum: String,
    val size: Long,
    val rename: String,
    val dll: String,
    val forArch: String
)

/** 可下载文件（从步骤中提取） */
data class RuntimeFile(
    val fileName: String,
    val url: String,
    val size: Long,
    val checksum: String,
    val rename: String
)

/** 运行库分类 */
enum class RuntimeCategory(val labelRes: String, val order: Int) {
    VC_RUNTIME("VC++ 运行库", 0),
    DOTNET(".NET 运行库", 1),
    CODEC("解码器 / 媒体", 2),
    GAME_DEPS("游戏依赖", 3),
    FONTS("字体 / CJK", 4),
    SYSTEM("系统组件", 5),
    OTHER("其他", 6);

    companion object {
        fun fromName(name: String, description: String): RuntimeCategory {
            val n = name.lowercase()
            val d = description.lowercase()
            return when {
                n.contains("vcredist") || n.contains("vcrun") || d.contains("visual c++") -> VC_RUNTIME
                n.contains("dotnet") || d.contains(".net framework") || d.contains(".net core") -> DOTNET
                n.contains("lavfilter") || n.contains("k-lite") || n.contains("klite") ||
                    n.contains("codec") || n.contains("mediafoundation") || n.contains("wmdecoder") ||
                    d.contains("decoder") || d.contains("codec pack") -> CODEC
                n.contains("xna") || n.contains("physx") || n.contains("xlive") || n.contains("xinput") ||
                    n.contains("xaudio") || n.contains("d3dx") || n.contains("d3dcompiler") ||
                    n.contains("vulkanrt") || n.contains("webview2") || n.contains("air") ||
                    n.contains("xact") || n.contains("x3daudio") || n.contains("xapofx") -> GAME_DEPS
                n.contains("font") || n.contains("cjk") || n.contains("sourcehan") -> FONTS
                n.contains("mdac") || n.contains("mfc") || n.contains("msxml") || n.contains("atmlib") ||
                    n.contains("art2k") || n.contains("cnc-ddraw") || n.contains("ddraw") -> SYSTEM
                else -> OTHER
            }
        }
    }
}

/** 解析 winlator-contents components.json */
object RuntimeLibraryParser {
    fun parse(json: String): List<RuntimeComponent> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("components") ?: return emptyList()
        val result = mutableListOf<RuntimeComponent>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isBlank()) continue
            val description = obj.optString("description")
            val provider = obj.optString("provider")
            val status = obj.optString("status", "ready")
            val deps = parseStringList(obj.optJSONArray("dependencies"))
            val steps = parseSteps(obj.optJSONArray("steps"))
            val primaryFile = steps.firstOrNull { it.url.isNotBlank() }?.let {
                RuntimeFile(
                    fileName = it.fileName,
                    url = it.url,
                    size = it.size,
                    checksum = it.checksum,
                    rename = it.rename
                )
            }
            result += RuntimeComponent(
                name = name,
                description = description,
                provider = provider,
                category = RuntimeCategory.fromName(name, description),
                status = status,
                dependencies = deps,
                steps = steps,
                primaryFile = primaryFile
            )
        }
        return result
    }

    private fun parseSteps(arr: JSONArray?): List<RuntimeStep> {
        if (arr == null) return emptyList()
        val result = mutableListOf<RuntimeStep>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result += RuntimeStep(
                action = obj.optString("action"),
                fileName = obj.optString("file_name"),
                url = obj.optString("url"),
                arguments = obj.optString("arguments"),
                checksum = obj.optString("file_checksum"),
                size = obj.optString("file_size").toLongOrNull() ?: 0L,
                rename = obj.optString("rename"),
                dll = obj.optString("dll"),
                forArch = obj.optString("for")
            )
        }
        return result
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }
}

/**
 * 运行库中文用途说明映射。
 * 按组件 name（小写、去标点）匹配，覆盖 winlator-contents 常见 120 个组件。
 * 无匹配时返回 null，UI 层回退到原英文 description。
 */
object RuntimeChineseHints {
    private val hints = mapOf(
        // VC++ 运行库
        "vcredist2022" to "VC++ 2015-2022 运行库，绝大多数新游戏和软件必需",
        "vcredist2019" to "VC++ 2015-2019 运行库，兼容 2015/2017/2019 编译的程序",
        "vcredist2017" to "VC++ 2017 运行库",
        "vcredist2015" to "VC++ 2015 运行库",
        "vcredist2013" to "VC++ 2013 运行库，部分老游戏需要",
        "vcredist2012" to "VC++ 2012 运行库",
        "vcredist2010" to "VC++ 2010 运行库，很多 2010 年代游戏需要",
        "vcredist2008" to "VC++ 2008 运行库，老游戏/老软件必需",
        "vcredist2005" to "VC++ 2005 运行库，老游戏必需",
        "vc2022" to "VC++ 2022 纯 DLL 包（Proton 11 arm64ec 适配），无需安装直接复制",
        "vc2019" to "VC++ 2019 纯 DLL 包",
        "vc2017" to "VC++ 2017 纯 DLL 包",
        "vc2015" to "VC++ 2015 纯 DLL 包",
        "vc2013" to "VC++ 2013 纯 DLL 包",
        "vc2010" to "VC++ 2010 纯 DLL 包",
        "vc2008" to "VC++ 2008 纯 DLL 包",
        "vc2005" to "VC++ 2005 纯 DLL 包",
        // .NET 运行库
        "dotnet48" to ".NET Framework 4.8，最新完整版，兼容 4.0~4.8 所有程序",
        "dotnet472" to ".NET Framework 4.7.2",
        "dotnet471" to ".NET Framework 4.7.1",
        "dotnet462" to ".NET Framework 4.6.2",
        "dotnet461" to ".NET Framework 4.6.1",
        "dotnet452" to ".NET Framework 4.5.2",
        "dotnet451" to ".NET Framework 4.5.1",
        "dotnet45" to ".NET Framework 4.5",
        "dotnet40" to ".NET Framework 4.0，老 .NET 程序必需",
        "dotnet35" to ".NET Framework 3.5（含 2.0/3.0），很多老软件需要",
        "dotnet30" to ".NET Framework 3.0",
        "dotnet20" to ".NET Framework 2.0",
        "dotnet11" to ".NET Framework 1.1，极老程序",
        "dotnet50" to ".NET 5.0 桌面运行库",
        "dotnetcore31" to ".NET Core 3.1 运行库",
        // 解码器 / 媒体
        "lavfilters" to "LAV Filters 解码器套装，解决视频/音频无法播放问题",
        "lavfilters0741" to "LAV Filters 0.74.1 解码器套装",
        "lavfilters0702" to "LAV Filters 0.70.2 解码器套装",
        "klite" to "K-Lite Codec Pack 编解码包，几乎支持所有视频格式",
        "klite196" to "K-Lite Codec Pack 19.6 标准版",
        "klitemega" to "K-Lite Mega Codec Pack 完整版，含全部编解码器",
        "k-lite" to "K-Lite Codec Pack 编解码包",
        "mediafoundation" to "Media Foundation 媒体框架组件",
        "wmdecoder" to "Windows Media 解码器",
        "ffdshow" to "ffdshow 解码器，老视频格式支持",
        // 游戏依赖
        "xna40" to "XNA Framework 4.0，XNA 游戏（如 Stardew Valley）必需",
        "xna31" to "XNA Framework 3.1，老 XNA 游戏必需",
        "physx" to "NVIDIA PhysX 物理引擎，支持 PhysX 的游戏需要",
        "xlive" to "Games for Windows Live，部分老游戏（如 GTA4、生化危机5）需要",
        "xinput" to "XInput 手柄支持库，Xbox 手柄兼容",
        "xaudio" to "XAudio2 音频库，部分游戏音频需要",
        "xact" to "XACT 音频引擎，老游戏音频支持",
        "x3daudio" to "X3DAudio 3D 音频库",
        "xapofx" to "XAPOFX 音频效果库",
        "d3dx9" to "DirectX 9 扩展库（d3dx9_xx.dll），大量老游戏必需",
        "d3dx943" to "DirectX 9.0c 2010 完整版，含所有 d3dx9/xaudio/xinput DLL",
        "d3dcompiler" to "D3DCompiler 着色器编译器",
        "vulkanrt" to "Vulkan Runtime 运行时，Vulkan 游戏/应用必需",
        "webview2" to "WebView2 运行库，基于 Edge 的嵌入式网页组件",
        "air" to "Adobe AIR 运行库，AIR 应用必需",
        "flash" to "Adobe Flash Player，老网页游戏/动画需要",
        "silverlight" to "Silverlight 插件",
        "openal" to "OpenAL 音频库，部分游戏 3D 音效需要",
        "ogg" to "Ogg Vorbis 音频解码器",
        // 字体 / CJK
        "cjkfonts" to "CJK 思源黑体，解决中文/日文/韩文乱码问题，强烈推荐安装",
        "sourcehan" to "思源黑体 CJK 字体",
        "notocjk" to "Noto CJK 字体",
        "fontfix" to "字体修复包",
        // 系统组件
        "mdac" to "MDAC 数据库访问组件",
        "mfc" to "MFC 库，老 MFC 程序需要",
        "msxml" to "MSXML XML 解析器",
        "atmlib" to "atmLib 库",
        "cnc-ddraw" to "cnc-ddraw DirectDraw 包装器，老 2D 游戏（红色警戒等）修复",
        "ddrawcompat" to "DDrawCompat DirectDraw 兼容层，老游戏修复",
        "dxvk" to "DXVK：DirectX 9/10/11 转 Vulkan，提升游戏性能和兼容性",
        "vkd3d" to "VKD3D-Proton：DirectX 12 转 Vulkan",
        "zink" to "Zink：OpenGL 转 Vulkan",
        // 其他
        "allfonts" to "全部常用字体包",
        "allruntimes" to "全部运行库合集",
        "directx" to "DirectX 最终用户运行库（2010 完整版）",
        "directx9" to "DirectX 9.0c 运行库",
        "gfwl" to "Games for Windows Live 市场",
        "ue4" to "Unreal Engine 4 先决条件",
        "ue5" to "Unreal Engine 5 先决条件",
        "unity" to "Unity 播放器先决条件"
    )

    fun lookup(name: String): String? {
        val key = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        // 精确匹配
        hints[key]?.let { return it }
        // 前缀匹配（如 vcredist2022_x64 → vcredist2022）
        for ((k, v) in hints) {
            if (key.startsWith(k) && key.length > k.length) return v
        }
        // 包含匹配（如 dotnet48_full → dotnet48）
        for ((k, v) in hints) {
            if (key.contains(k) && k.length >= 4) return v
        }
        return null
    }
}
