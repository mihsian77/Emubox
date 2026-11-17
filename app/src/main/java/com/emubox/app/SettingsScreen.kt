package com.emubox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.template.CollapseRow
import com.emubox.app.ui.template.PageTitleRow
import com.emubox.app.ui.template.ResultCountRow
import com.emubox.app.ui.template.ConclusionCard
import com.emubox.app.ui.template.ConclusionTone
import com.emubox.app.ui.template.DetailActionBar
import com.emubox.app.ui.template.FusionCard
import com.emubox.app.ui.template.SectionHeader
import com.emubox.app.ui.template.SectionTabs
import com.emubox.app.ui.template.SettingChoiceRow
import com.emubox.app.ui.template.SettingGroup
import com.emubox.app.ui.template.SettingRow
import com.emubox.app.ui.template.SpecItem
import com.emubox.app.ui.template.SpecStrip
import com.emubox.app.ui.template.listPageHeader
import com.emubox.app.ui.theme.Fusion
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    colorTheme: ColorTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onColorThemeChange: (ColorTheme) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    uiScale: UiScale,
    onUiScaleChange: (UiScale) -> Unit,
    onSourceCatalogChanged: () -> Unit,
    onOpenDownloads: (() -> Unit)? = null,
    showBack: Boolean = true
) {
    if (showBack) {
        BackHandler { onBack() }
    }

    val context = LocalContext.current
    val downloadsDefaultLabel = appString(R.string.downloads_default)
    var currentFolderUri by remember { mutableStateOf(SettingsManager.getDownloadFolderUri()) }
    var displayPath by remember { mutableStateOf<String?>(null) }
    var matchDriversByDevice by remember { mutableStateOf(SettingsManager.getMatchDriversByDevice()) }
    var translateEngine by remember { mutableStateOf(SettingsManager.getTranslateEngine()) }
    var translateEmail by remember { mutableStateOf(SettingsManager.getTranslateEmail()) }
    var deepSeekKey by remember { mutableStateOf(SettingsManager.getDeepSeekKey()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                SettingsManager.setDownloadFolderUri(uri.toString())
                currentFolderUri = uri.toString()
                val docFile = DocumentFile.fromTreeUri(context, uri)
                displayPath = docFile?.name ?: uri.path
            }
        }
    )

    LaunchedEffect(currentFolderUri, appLanguage) {
        displayPath = if (currentFolderUri != null) {
            val uri = Uri.parse(currentFolderUri)
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.path
        } else {
            downloadsDefaultLabel
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appString(R.string.settings)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = appString(R.string.back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 「我的」入口：下载页不再占一级导航，改从这里进入
            if (onOpenDownloads != null) {
                item {
                    SettingGroup {
                        SettingRow(
                            title = appString(R.string.tab_download),
                            subtitle = appString(R.string.downloads_entry_desc),
                            showDivider = false,
                            onClick = onOpenDownloads,
                            trailing = {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Fusion.OnSurfaceFaint
                                )
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.appearance),
                    subtitle = appString(R.string.appearance_desc)
                )
            }
            item {
                // 外观四项合为一组：一组一张卡，项与项之间只用分隔线分层
                SettingGroup {
                    SettingChoiceRow(
                        title = appString(R.string.app_language),
                        subtitle = appString(R.string.language_desc),
                    ) {
                        LanguageSelector(selected = appLanguage, onSelected = onAppLanguageChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.theme_mode),
                        subtitle = appString(R.string.theme_mode_desc),
                    ) {
                        ThemeModeSelector(selected = themeMode, onSelected = onThemeModeChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.ui_scale_title),
                        subtitle = appString(R.string.ui_scale_desc),
                    ) {
                        UiScaleSelector(selected = uiScale, onSelected = onUiScaleChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.color_theme),
                        subtitle = appString(R.string.color_theme_desc),
                        showDivider = false,
                    ) {
                        ColorThemeSelector(selected = colorTheme, onSelected = onColorThemeChange)
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.translate_section),
                    subtitle = appString(R.string.translate_section_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.translate_engine)) {
                    val isMyMemory = translateEngine != "deepseek"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isMyMemory,
                            onClick = {
                                translateEngine = "mymemory"
                                SettingsManager.setTranslateEngine("mymemory")
                            },
                            label = { Text(appString(R.string.translate_engine_mymemory)) }
                        )
                        FilterChip(
                            selected = !isMyMemory,
                            onClick = {
                                translateEngine = "deepseek"
                                SettingsManager.setTranslateEngine("deepseek")
                            },
                            label = { Text(appString(R.string.translate_engine_deepseek)) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = appString(
                            if (isMyMemory) R.string.translate_engine_mymemory_note
                            else R.string.translate_engine_deepseek_note
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (isMyMemory) {
                        OutlinedTextField(
                            value = translateEmail,
                            onValueChange = {
                                translateEmail = it
                                SettingsManager.setTranslateEmail(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(appString(R.string.translate_email)) },
                            placeholder = { Text(appString(R.string.translate_email_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = appString(R.string.translate_email_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedTextField(
                            value = deepSeekKey,
                            onValueChange = {
                                deepSeekKey = it
                                SettingsManager.setDeepSeekKey(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(appString(R.string.translate_deepseek_key)) },
                            placeholder = { Text(appString(R.string.translate_deepseek_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = appString(R.string.translate_deepseek_key_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.sources),
                    subtitle = appString(R.string.sources_desc)
                )
            }

            item {
                SettingGroup {
                    SettingRow(
                        title = appString(R.string.match_drivers_by_device),
                        subtitle = appString(R.string.match_drivers_by_device_desc),
                        showDivider = false,
                        trailing = {
                            Switch(
                                checked = matchDriversByDevice,
                                onCheckedChange = { enabled ->
                                    matchDriversByDevice = enabled
                                    SettingsManager.setMatchDriversByDevice(enabled)
                                    onSourceCatalogChanged()
                                }
                            )
                        }
                    )
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.downloads),
                    subtitle = appString(R.string.downloads_settings_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.download_folder)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayPath ?: appString(R.string.downloads_default),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (currentFolderUri == null) appString(R.string.system_downloads_folder) else appString(R.string.custom_folder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = appString(R.string.download_folder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(appString(R.string.choose_folder))
                    }

                    if (currentFolderUri != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                SettingsManager.clearDownloadFolder()
                                currentFolderUri = null
                                displayPath = downloadsDefaultLabel
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(appString(R.string.reset_to_default))
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.accelerator_title),
                    subtitle = appString(R.string.accelerator_desc)
                )
            }

            item {
                AcceleratorSettingsCard()
            }

            item {
                GithubTokenCard()
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.about_section),
                    subtitle = appString(R.string.about_upstream)
                )
            }

            item {
                AboutCard()
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * 设置页分组标题。
 *
 * 旧实现给每组配一个 60dp 高的图标盒子，等于用整行高度换零信息量；
 * 现在按排版规格 C 收敛成「小字 + 弱色」的一行标题。
 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String
) {
    SectionHeader(title = title, subtitle = subtitle)
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingGroup(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                color = Fusion.OnSurface,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ── GitHub API Token（可选）──────────────────────────────────────

/**
 * 未认证的 GitHub API 限额 60 次/小时/IP。冷启动会并发拉 5 个驱动源，
 * 几次强刷就会 403，表现为「驱动列表突然空了」。填 Token 后 5000 次/小时。
 * Token 用 Keystore 加密保存，且只发给 api.github.com（经代理时自动剥离）。
 */
@Composable
private fun GithubTokenCard() {
    var input by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(SettingsManager.hasGithubToken()) }
    var justSaved by remember { mutableStateOf(false) }

    SettingsCard(title = appString(R.string.settings_github_token_title)) {
        Text(
            appString(R.string.settings_github_token_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; justSaved = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(appString(R.string.settings_github_token_hint)) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    SettingsManager.setGithubToken(input)
                    configured = SettingsManager.hasGithubToken()
                    justSaved = configured
                    input = ""
                },
                enabled = input.isNotBlank()
            ) { Text(appString(R.string.save)) }

            if (configured) {
                TextButton(
                    onClick = {
                        SettingsManager.setGithubToken("")
                        configured = false
                        justSaved = false
                    }
                ) { Text(appString(R.string.delete)) }
            }

            if (justSaved) {
                Spacer(Modifier.height(0.dp))
                Text(
                    appString(R.string.settings_github_token_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── 国内下载加速设置 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceleratorSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Accelerator.getMode()) }
    var manualNodeId by remember { mutableStateOf(Accelerator.getManualNode().id) }
    var latencies by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var speeds by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }
    var autoResult by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refreshMode() { mode = Accelerator.getMode() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(appString(R.string.accelerator_mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == Accelerator.Mode.AUTO,
                    onClick = { Accelerator.setMode(Accelerator.Mode.AUTO); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_auto)) }
                )
                FilterChip(
                    selected = mode == Accelerator.Mode.MANUAL,
                    onClick = { Accelerator.setMode(Accelerator.Mode.MANUAL); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_manual)) }
                )
                FilterChip(
                    selected = mode == Accelerator.Mode.OFF,
                    onClick = { Accelerator.setMode(Accelerator.Mode.OFF); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_off)) }
                )
            }

            if (mode == Accelerator.Mode.MANUAL) {
                Spacer(Modifier.height(16.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = Accelerator.ALL_NODES.firstOrNull { it.id == manualNodeId }?.displayName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text(appString(R.string.accelerator_node)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        shape = RoundedCornerShape(18.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Accelerator.ALL_NODES.forEach { node ->
                            DropdownMenuItem(
                                text = { Text(node.displayName) },
                                onClick = {
                                    manualNodeId = node.id
                                    Accelerator.setManualNode(node)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            val autoRes = autoResult
            if (mode == Accelerator.Mode.AUTO && autoRes != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = appString(R.string.accelerator_auto_result, autoRes.first, autoRes.second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    testing = true
                    autoResult = null
                    scope.launch(Dispatchers.IO) {
                        val (latMap, speedMap) = Accelerator.testAllLatenciesAndSpeeds()
                        latencies = latMap
                        speeds = speedMap
                        if (mode == Accelerator.Mode.AUTO) {
                            // 综合评分（延迟 40% + 速度 60%）选最优，避免"直连延迟低但网速慢"被误选
                            val best = Accelerator.selectBestNode(latMap, speedMap)
                            Accelerator.cacheBestNode(best)
                            val lat = latMap[best.id]
                            autoResult = best.displayName to Accelerator.formatLatency(context, lat)
                        }
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.accelerator_testing))
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.accelerator_test_latency))
                }
            }

            if (latencies.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Accelerator.ALL_NODES.forEach { node ->
                    val ms = latencies[node.id]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (ms != null) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (ms != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = node.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (node.trafficKinds.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = appString(R.string.raw_only),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = Accelerator.formatLatency(context, ms),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    ms == null -> MaterialTheme.colorScheme.error
                                    ms < 300 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            val speed = speeds[node.id]
                            if (speed != null) {
                                Text(
                                    text = Accelerator.formatSpeed(speed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        speed >= 5 * 1024 * 1024 -> MaterialTheme.colorScheme.primary
                                        speed >= 1024 * 1024 -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            } else if (ms != null) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }

            // 节点来源声明（MirrorHub，MIT 协议；不提供跳转入口，避免节点仓库被滥用）
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = appString(R.string.mirrorhub_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 关于 / 汉化署名 ────────────────────────────────────────────

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) { "1.0.0-cn" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = appString(R.string.about_translation),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = appString(R.string.about_translation_by),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = appString(R.string.about_version, versionName ?: "1.0.0-cn"),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = appString(R.string.about_upstream),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = appString(R.string.about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Column {
                listOf(
                    appString(R.string.about_project) to "https://github.com/mihsian77/Emubox",
                    appString(R.string.about_feedback) to "https://github.com/mihsian77/Emubox/issues",
                    appString(R.string.about_support) to "https://afdian.com/a/moon279"
                ).forEach { (label, url) ->
                    TextButton(
                        onClick = { openNewsLink(context, url) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = appString(R.string.app_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = AppLanguage.values().toList()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = languageLabel(selected),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(appString(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(18.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { language ->
                DropdownMenuItem(
                    text = { Text(languageLabel(language)) },
                    leadingIcon = if (language == selected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelected(language)
                    }
                )
            }
        }
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> appString(R.string.language_system)
    AppLanguage.ENGLISH -> appString(R.string.language_english)
    AppLanguage.CHINESE -> appString(R.string.language_chinese)
}

@Composable
private fun UiScaleSelector(
    selected: UiScale,
    onSelected: (UiScale) -> Unit
) {
    val choices = UiScale.entries.map { it to appString(it.labelRes) }
    // 四个选项标签都很短，一行放完；分两行等于白占半行高度
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { (scale, label) ->
            FilterChip(
                selected = selected == scale,
                onClick = { onSelected(scale) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit
) {
    val choices = listOf(
        ThemeMode.SYSTEM to Pair(appString(R.string.theme_system), Icons.Default.SettingsBrightness),
        ThemeMode.LIGHT to Pair(appString(R.string.theme_light), Icons.Default.LightMode),
        ThemeMode.DARK to Pair(appString(R.string.theme_dark), Icons.Default.DarkMode),
        ThemeMode.AMOLED to Pair("AMOLED", Icons.Default.Contrast)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (mode, data) ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        label = { Text(data.first) },
                        leadingIcon = {
                            Icon(
                                imageVector = data.second,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorThemeSelector(
    selected: ColorTheme,
    onSelected: (ColorTheme) -> Unit
) {
    val choices = listOf(
        ColorTheme.DYNAMIC to appString(R.string.theme_dynamic),
        ColorTheme.EMUHUB to "EmuBox",
        ColorTheme.BLUE to appString(R.string.theme_blue),
        ColorTheme.PURPLE to appString(R.string.theme_purple),
        ColorTheme.ORANGE to appString(R.string.theme_orange),
        ColorTheme.CN to appString(R.string.theme_cn)
    )

    // 六个主题横排一屏放不下，横滑等于把后两个藏起来（用户不知道能滑）。
    // 改成 3×2 换行，一眼看全，不用猜。
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (theme, title) ->
                    FilterChip(
                        selected = selected == theme,
                        onClick = { onSelected(theme) },
                        label = { Text(title, maxLines = 1) },
                        leadingIcon = if (selected == theme) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
