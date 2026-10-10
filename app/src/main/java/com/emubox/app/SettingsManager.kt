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

private const val PREFS_SETTINGS = "emu_hub_settings"
private const val KEY_DOWNLOAD_FOLDER_URI = "download_folder_uri"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_COLOR_THEME = "color_theme"
private const val KEY_DOWNLOAD_SECTION = "download_section"
private const val KEY_TURNIP_SOURCE = "turnip_source"
private const val KEY_QUALCOMM_SOURCE = "qualcomm_source"
private const val KEY_SOURCE_CATALOG_URL = "source_catalog_url"
private const val KEY_APP_LANGUAGE = "app_language"
private const val KEY_ACCELERATOR_MODE = "accelerator_mode"
private const val KEY_ACCELERATOR_NODE = "accelerator_node"
private const val KEY_MATCH_DRIVERS_BY_DEVICE = "match_drivers_by_device"

object SettingsManager {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    fun getDownloadFolderUri(): String? = prefs.getString(KEY_DOWNLOAD_FOLDER_URI, null)

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

    fun getQualcommSource(): String =
        prefs.getString(KEY_QUALCOMM_SOURCE, "stevenmxz-qualcomm") ?: "stevenmxz-qualcomm"

    fun setQualcommSource(sourceId: String) {
        prefs.edit().putString(KEY_QUALCOMM_SOURCE, sourceId).apply()
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

    private fun preferenceKey(type: String, value: String): String =
        "download_selection_${type}_${value}"

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T {
        val saved = prefs.getString(key, null) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == saved } ?: fallback
    }
}
