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
