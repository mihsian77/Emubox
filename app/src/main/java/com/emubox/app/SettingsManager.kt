package com.emubox.app

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED
}

enum class ColorTheme {
    DYNAMIC,
    EMUHUB,
    BLUE,
    PURPLE,
    ORANGE,
    CN
}

/** 界面大小档位（全局 density 缩放：字体/间距/图标一体缩放） */
enum class UiScale(val scale: Float, val labelRes: Int) {
    SMALL(0.85f, R.string.ui_scale_small),
    STANDARD(1.0f, R.string.ui_scale_standard),
    LARGE(1.15f, R.string.ui_scale_large),
    XLARGE(1.3f, R.string.ui_scale_xlarge)
}

private const val PREFS_SETTINGS = "emu_hub_settings"
private const val KEY_STORAGE_PERMISSION_ASKED = "storage_permission_asked"
private const val KEY_DOWNLOAD_FOLDER_URI = "download_folder_uri"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_COLOR_THEME = "color_theme"
private const val KEY_DOWNLOAD_SECTION = "download_section"
private const val KEY_TURNIP_SOURCE = "turnip_source"
private const val KEY_PANVK_SOURCE = "panvk_source"
private const val KEY_QUALCOMM_SOURCE = "qualcomm_source"
private const val KEY_XCLIPSE_SOURCE = "xclipse_source"
private const val KEY_WRAPPER_SOURCE = "wrapper_source"
private const val KEY_SOURCE_CATALOG_URL = "source_catalog_url"
private const val KEY_APP_LANGUAGE = "app_language"
private const val KEY_UI_SCALE = "ui_scale"
private const val KEY_ACCELERATOR_MODE = "accelerator_mode"
private const val KEY_ACCELERATOR_NODE = "accelerator_node"
private const val KEY_MATCH_DRIVERS_BY_DEVICE = "match_drivers_by_device"
private const val KEY_DEEPSEEK_KEY = "secret_deepseek_key"
private const val KEY_GITHUB_TOKEN = "secret_github_token"

object SettingsManager {
    private lateinit var prefs: SharedPreferences

    /** 供 SecretStore（Keystore 加解密）使用；存 applicationContext 不会泄漏 Activity */
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        // 旧版本把 DeepSeek Key / GitHub Token 明文存 prefs，升级后一次性搬进 Keystore
        runCatching {
            SecretStore.migrateLegacy(
                appContext, prefs, "translate_deepseek_key", KEY_DEEPSEEK_KEY
            )
        }
    }

    fun isStoragePermissionAsked(): Boolean = prefs.getBoolean(KEY_STORAGE_PERMISSION_ASKED, false)

    fun setStoragePermissionAsked() {
        prefs.edit().putBoolean(KEY_STORAGE_PERMISSION_ASKED, true).apply()
    }

    fun getDownloadFolderUri(): String? = prefs.getString(KEY_DOWNLOAD_FOLDER_URI, null)

    /** 下载区 SAF 自动授权是否已弹过（用户拒绝后不再自动打扰，可手动按钮重试）。 */
    fun getDownloadAuthDismissed(): Boolean =
        prefs.getBoolean("download_auth_dismissed", false)

    fun setDownloadAuthDismissed(v: Boolean) {
        prefs.edit().putBoolean("download_auth_dismissed", v).apply()
    }

    fun setDownloadFolderUri(uriString: String) {
        prefs.edit().putString(KEY_DOWNLOAD_FOLDER_URI, uriString).apply()
    }

    fun clearDownloadFolder() {
        prefs.edit().remove(KEY_DOWNLOAD_FOLDER_URI).apply()
    }

    fun getThemeMode(): ThemeMode = enumPreference(KEY_THEME_MODE, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getColorTheme(): ColorTheme = enumPreference(KEY_COLOR_THEME, ColorTheme.DYNAMIC)

    fun getUiScale(): UiScale = enumPreference(KEY_UI_SCALE, UiScale.STANDARD)

    fun setUiScale(scale: UiScale) {
        prefs.edit().putString(KEY_UI_SCALE, scale.name).apply()
    }

    fun getAppLanguage(): AppLanguage = enumPreference(KEY_APP_LANGUAGE, AppLanguage.SYSTEM)

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_APP_LANGUAGE, language.name).apply()
    }

    // ── 国内下载加速 ──────────────────────────────────────────────

    fun getAcceleratorMode(): Accelerator.Mode =
        enumPreference(KEY_ACCELERATOR_MODE, Accelerator.Mode.AUTO)

    fun setAcceleratorMode(mode: Accelerator.Mode) {
        prefs.edit().putString(KEY_ACCELERATOR_MODE, mode.name).apply()
        Accelerator.clearCache()
    }

    fun getAcceleratorNodeId(): String =
        prefs.getString(KEY_ACCELERATOR_NODE, Accelerator.BUILTIN_NODES.first().id)
            ?: Accelerator.BUILTIN_NODES.first().id

    fun setAcceleratorNodeId(nodeId: String) {
        prefs.edit().putString(KEY_ACCELERATOR_NODE, nodeId).apply()
        Accelerator.clearCache()
    }

    fun getMatchDriversByDevice(): Boolean =
        prefs.getBoolean(KEY_MATCH_DRIVERS_BY_DEVICE, true)

    fun setMatchDriversByDevice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MATCH_DRIVERS_BY_DEVICE, enabled).apply()
    }

    fun setColorTheme(theme: ColorTheme) {
        prefs.edit().putString(KEY_COLOR_THEME, theme.name).apply()
    }

    // Download hub state. These values are intentionally persisted instead of
    // living only in Compose state, so opening Downloads/Settings (or restarting
    // the app) does not reset the user's selected category/version/file.
    fun getSelectedDownloadSection(): String =
        prefs.getString(KEY_DOWNLOAD_SECTION, "turnip") ?: "turnip"

    fun setSelectedDownloadSection(section: String) {
        prefs.edit().putString(KEY_DOWNLOAD_SECTION, section).apply()
    }

    fun getTurnipSource(): String =
        prefs.getString(KEY_TURNIP_SOURCE, "StevenMXZ") ?: "StevenMXZ"

    fun setTurnipSource(source: String) {
        prefs.edit().putString(KEY_TURNIP_SOURCE, source).apply()
    }

    fun getPanvkSource(): String =
        prefs.getString(KEY_PANVK_SOURCE, "panvk-g720") ?: "panvk-g720"

    fun setPanvkSource(sourceId: String) {
        prefs.edit().putString(KEY_PANVK_SOURCE, sourceId).apply()
    }

    fun getQualcommSource(): String =
        prefs.getString(KEY_QUALCOMM_SOURCE, "stevenmxz-qualcomm") ?: "stevenmxz-qualcomm"

    fun setQualcommSource(sourceId: String) {
        prefs.edit().putString(KEY_QUALCOMM_SOURCE, sourceId).apply()
    }

    fun getXclipseSource(): String =
        prefs.getString(KEY_XCLIPSE_SOURCE, "xclipse940-radv") ?: "xclipse940-radv"

    fun setXclipseSource(sourceId: String) {
        prefs.edit().putString(KEY_XCLIPSE_SOURCE, sourceId).apply()
    }

    fun getWrapperSource(): String =
        prefs.getString(KEY_WRAPPER_SOURCE, "wrapper-banner") ?: "wrapper-banner"

    fun setWrapperSource(sourceId: String) {
        prefs.edit().putString(KEY_WRAPPER_SOURCE, sourceId).apply()
    }

    fun getComponentSource(type: String): String? =
        prefs.getString(preferenceKey("source", type), null)

    fun setComponentSource(type: String, sourceId: String) {
        prefs.edit().putString(preferenceKey("source", type), sourceId).apply()
    }

    fun getSourceCatalogUrl(): String =
        prefs.getString(KEY_SOURCE_CATALOG_URL, DEFAULT_SOURCE_CATALOG_URL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SOURCE_CATALOG_URL

    fun setSourceCatalogUrl(url: String) {
        prefs.edit().putString(KEY_SOURCE_CATALOG_URL, url.trim()).apply()
    }

    fun resetSourceCatalogUrl() {
        prefs.edit().remove(KEY_SOURCE_CATALOG_URL).apply()
    }

    fun getSelectedReleaseTag(selectionKey: String): String? =
        prefs.getString(preferenceKey("release", selectionKey), null)

    fun setSelectedReleaseTag(selectionKey: String, tag: String) {
        prefs.edit().putString(preferenceKey("release", selectionKey), tag).apply()
    }

    fun getSelectedAssetName(selectionKey: String, releaseTag: String): String? =
        prefs.getString(preferenceKey("asset", "$selectionKey::$releaseTag"), null)

    fun setSelectedAssetName(selectionKey: String, releaseTag: String, assetName: String) {
        prefs.edit()
            .putString(preferenceKey("asset", "$selectionKey::$releaseTag"), assetName)
            .apply()
    }

    fun getSelectedComponentVersion(selectionKey: String): String? =
        prefs.getString(preferenceKey("component", selectionKey), null)

    fun setSelectedComponentVersion(selectionKey: String, version: String) {
        prefs.edit().putString(preferenceKey("component", selectionKey), version).apply()
    }

    // ---------------- 翻译引擎 ----------------
    // 默认 MyMemory（免费、国内直连，匿名每 IP 每天 5000 字符）。
    // 填了邮箱走官方 de 参数 → 50000 字符/天；填了 DeepSeek Key 则优先走 DeepSeek。

    fun getTranslateEngine(): String = prefs.getString("translate_engine", "mymemory") ?: "mymemory"

    fun setTranslateEngine(engine: String) {
        prefs.edit().putString("translate_engine", engine).apply()
        TranslationEngine.clearCache()
    }

    /** DeepSeek Key：Keystore 加密存储（旧版明文已在 init 时迁移并删除） */
    fun getDeepSeekKey(): String =
        runCatching { SecretStore.get(appContext, KEY_DEEPSEEK_KEY) }.getOrDefault("")

    fun setDeepSeekKey(key: String) {
        SecretStore.put(appContext, KEY_DEEPSEEK_KEY, key.trim())
        TranslationEngine.clearCache()
    }

    // ── GitHub API Token（可选）──────────────────────────────────
    // 未认证的 GitHub API 限额 60 次/小时/IP：冷启动 5 个源 + 强刷就会 403。
    // 填了 token 后限额 5000 次/小时。token 只用于 api.github.com，且经过
    // 第三方加速节点时会主动剥离 Authorization 头（Accelerator.shouldStripAuthorization）。
    fun getGithubToken(): String =
        runCatching { SecretStore.get(appContext, KEY_GITHUB_TOKEN) }.getOrDefault("")

    fun setGithubToken(token: String) {
        SecretStore.put(appContext, KEY_GITHUB_TOKEN, token.trim())
        clearRemoteCaches()
    }

    fun hasGithubToken(): Boolean = getGithubToken().isNotBlank()

    /** 驱动首次加载的风险确认（第三方驱动包内含可执行 native 代码） */
    fun isDriverRiskAcknowledged(): Boolean =
        prefs.getBoolean("driver_risk_acknowledged", false)

    fun setDriverRiskAcknowledged() {
        prefs.edit().putBoolean("driver_risk_acknowledged", true).apply()
    }

    /** MyMemory 提升配额的邮箱（官方 de 参数，可留空）。 */
    fun getTranslateEmail(): String = prefs.getString("translate_email", "") ?: ""

    fun setTranslateEmail(email: String) {
        prefs.edit().putString("translate_email", email.trim()).apply()
        TranslationEngine.clearCache()
    }

    private fun preferenceKey(type: String, value: String): String =
        "download_selection_${type}_${value}"

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T {
        val saved = prefs.getString(key, null) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == saved } ?: fallback
    }
}
