package com.emubox.app

/**
 * 下载记录的项目归属，用于下载库按项目分组。
 *
 * 归属信息不额外落库，直接从下载 URL 解析——GitHub 的下载地址本身就带着
 * owner / repo / tag，比在调用链上层层传参更不容易漏（本项目只有一个
 * `startDownload` 入口，但那附近的参数已经很多，再加 3 个字段容易传错）。
 *
 * 解析失败的（国外 CDN 签名直链、商店分发）返回 null，由调用方退回
 * subPath 或「其他文件」兜底，绝不猜。
 */
data class DownloadOrigin(
    val owner: String,
    val repo: String,
    /** release tag 或分支名。直链且无 tag 时为空串 */
    val version: String = ""
) {
    /** 分组键：同一仓库的多个版本、多个文件合成一组 */
    val key: String get() = "${owner}/${repo}".lowercase()

    /** 组标题：仓库名比 owner 更能说明「这是什么项目」 */
    val label: String get() = repo

    /** 版本展示：v1.4.0 / latest 都原样保留，空则不显示 */
    val versionLabel: String get() = version
}

object DownloadOrigins {

    /** `/releases/download/{tag}/{asset}` —— 第三段是 tag */
    private val GITHUB_RELEASE = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/""",
        RegexOption.IGNORE_CASE
    )

    /** `/releases/latest/download/{asset}` —— 没有 tag，版本记为 latest */
    private val GITHUB_RELEASE_LATEST = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/releases/latest/download/""",
        RegexOption.IGNORE_CASE
    )

    private val GITHUB_RAW = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/(?:raw|archive)/refs?/(?:heads|tags)?/?([^/]+)""",
        RegexOption.IGNORE_CASE
    )

    private val GITHUB_BLOB = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)""",
        RegexOption.IGNORE_CASE
    )

    private val JSDELIVR = Regex(
        """^https?://(?:cdn|fastly)\.jsdelivr\.net/gh/([^/]+)/([^@/]+)@([^/]+)""",
        RegexOption.IGNORE_CASE
    )

    private val RAW_HOST = Regex(
        """^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从原始下载 URL 解析归属。传入的应当是未加速的直链
     * （`originalUrl`），加速后的 URL 已被改写成代理域名，解析不出来。
     */
    fun parse(url: String?): DownloadOrigin? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        // latest 分支必须先判：它的路径里也含 /releases/ 与 /download/
        GITHUB_RELEASE_LATEST.find(u)?.let { m ->
            val (owner, repo) = m.destructured
            return DownloadOrigin(owner, repo, "latest").sanitized()
        }
        GITHUB_RELEASE.find(u)?.let { m ->
            val (owner, repo, tag) = m.destructured
            return DownloadOrigin(owner, repo, tag).sanitized()
        }
        JSDELIVR.find(u)?.let { m ->
            val (owner, repo, ref) = m.destructured
            return DownloadOrigin(owner, repo, ref).sanitized()
        }
        RAW_HOST.find(u)?.let { m ->
            val (owner, repo, ref) = m.destructured
            return DownloadOrigin(owner, repo, ref).sanitized()
        }
        GITHUB_BLOB.find(u)?.let { m ->
            val (owner, repo, ref) = m.destructured
            return DownloadOrigin(owner, repo, ref).sanitized()
        }
        GITHUB_RAW.find(u)?.let { m ->
            val (owner, repo, ref) = m.destructured
            return DownloadOrigin(owner, repo, ref).sanitized()
        }
        return null
    }

    /**
     * 兜底分组键：解析不出仓库时，用下载目录里的分类（`Drivers` /
     * `Components/Bundle-xxx`）当项目名，至少能把同类文件放在一起。
     */
    fun fallbackKey(fileName: String, subPath: String): String {
        val head = subPath.trim().trim('/').substringBefore('/')
        if (head.isNotBlank()) return "dir:$head"
        return "file:${fileExtensionKey(fileName)}"
    }

    /** 兜底组标题 */
    fun fallbackLabel(subPath: String, fileName: String): String {
        val head = subPath.trim().trim('/').substringBefore('/')
        if (head.isNotBlank()) return head
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext.isBlank()) "其他文件" else "${ext.uppercase()} 文件"
    }

    private fun fileExtensionKey(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase().ifBlank { "none" }

    private fun DownloadOrigin.sanitized(): DownloadOrigin? {
        if (owner.isBlank() || repo.isBlank()) return null
        return DownloadOrigin(owner, repo.removeSuffix(".git"), version)
    }
}
