package com.emubox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个驱动/组件源的「给谁用 + 怎么装 + 注意」文案。
 *
 * 文案不从代码里硬编码，也不现编：全部来自 `assets/source-profiles.json`，
 * 内容只写能从源仓库 README 或发布说明里读到的事实（支持的 GPU/芯片、
 * 安装方式、作者自己写的警告）。找不到对应文案时整块不显示，
 * 而不是拿一句猜出来的兼容性描述顶上。
 */
data class SourceProfile(
    /** 一句话：这个源适合谁（机型/GPU/场景） */
    val fit: String = "",
    /** 安装步骤，1–3 条，按顺序做 */
    val how: List<String> = emptyList(),
    /** 什么时候别用它；可为空 */
    val caution: String = ""
) {
    val isEmpty: Boolean get() = fit.isBlank() && how.isEmpty() && caution.isBlank()
}

object SourceProfiles {

    private var sources: Map<String, SourceProfile> = emptyMap()
    private var componentTypes: Map<String, SourceProfile> = emptyMap()
    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val text = context.assets.open(FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            sources = parse(root.optJSONObject("sources"))
            componentTypes = parse(root.optJSONObject("componentTypes"))
        }
    }

    private fun parse(obj: JSONObject?): Map<String, SourceProfile> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, SourceProfile>()
        obj.keys().forEach { key ->
            val item = obj.optJSONObject(key) ?: return@forEach
            out[key] = SourceProfile(
                fit = item.optString("fit").trim(),
                how = when (val raw = item.opt("how")) {
                    is JSONArray -> buildList {
                        for (i in 0 until raw.length()) {
                            raw.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                    is String -> listOf(raw).filter { it.isNotBlank() }
                    else -> emptyList()
                },
                caution = item.optString("caution").trim()
            )
        }
        return out
    }

    /** 驱动/组件源（按 catalog.json 与 driver-registry.json 的源 id） */
    fun ofSource(context: Context, id: String?): SourceProfile? {
        if (id.isNullOrBlank()) return null
        ensureLoaded(context)
        return sources[id]
    }

    /** 组件类型（Wine / DXVK / Box64 …） */
    fun ofComponentType(context: Context, type: String?): SourceProfile? {
        if (type.isNullOrBlank()) return null
        ensureLoaded(context)
        return componentTypes[type]
    }

    private const val FILE = "source-profiles.json"
}
