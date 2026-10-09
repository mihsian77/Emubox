package com.emuhub.cn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 运行库目录仓库：从 The412Banner/winlator-contents 拉取 components.json 并解析。
 * 文件下载走 Accelerator 加速（GitHub Release 资产）。
 */
object RuntimeLibraryRepository {
    private const val DEFAULT_CATALOG_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun load(url: String = DEFAULT_CATALOG_URL): List<RuntimeComponent> =
        withContext(Dispatchers.IO) {
            try {
                val acceleratedUrl = Accelerator.rewriteUrl(url)
                val request = Request.Builder().url(acceleratedUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext emptyList()
                    RuntimeLibraryParser.parse(body)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 按分类分组，分类内按 name 排序 */
    fun groupByCategory(components: List<RuntimeComponent>): Map<RuntimeCategory, List<RuntimeComponent>> =
        components.groupBy { it.category }
            .mapValues { (_, list) -> list.sortedBy { it.name } }
            .toSortedMap(compareBy { it.order })
}
