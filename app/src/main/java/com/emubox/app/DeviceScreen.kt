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


/**
 * 设备 Tab：展示设备摘要 + Vulkan 采集信息。
 * Vulkan 信息通过 native 引擎（dlopen 系统 libvulkan）采集，
 * 替换原先仅靠 GL_RENDERER 字符串解析的方案。
 */
@Composable
fun DeviceScreen(
    modifier: Modifier = Modifier,
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    /** 主库条目：可玩库要统计「每个平台库里有几个能装的」 */
    entries: List<EmulatorEntry> = emptyList(),
    onNavigateToDrivers: (() -> Unit)? = null,
    onOpenDriverFiles: ((String) -> Unit)? = null,
    /** 点可玩库里的平台 → 跳到模拟器页并带上该平台筛选 */
    onOpenPlatform: ((String) -> Unit)? = null,
    /** 顶栏指南按钮的触发信号（自增计数）：>0 时打开指南 */
    guideSignal: Int = 0
) {
    var vulkanPayload by remember { mutableStateOf<VulkanInfoPayload?>(null) }
    var vulkanLoading by remember { mutableStateOf(true) }
    var vulkanError by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    // 最近一次驱动实测的跑分：由「实测」里的驱动实测卡回传，用于设备指南的实测脚注
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    var showDeviceGuide by remember { mutableStateOf(false) }
    // 渲染实测可换的目标驱动：只取已下载完成的驱动包（与驱动页同一套判定口径）。
    // 页面级 remember：设备页数据本来就是进入时采集一次，列表在页面存活期内固定，
    // 避免每次重组重建列表把卡片里选中的驱动重置掉。
    val driverTargets = remember {
        DownloadsManager.completedDownloads
            .filter {
                it.fileName.endsWith(".zip", true) || it.fileName.endsWith(".adpkg", true)
            }
            .filter { dl ->
                if (dl.subPath.isNotBlank()) dl.subPath.startsWith("Drivers", true)
                else listOf(
                    "turnip", "qualcomm", "adreno", "mali", "panvk", "driver", "vulkan", "mesa"
                ).any { dl.fileName.contains(it, true) }
            }
            .map { dl ->
                DriverTarget(
                    id = dl.id,
                    // 不砍到 24 字：`Turnip_Gen8_v26.0_R2_A6xx` 与 `_A7xx`
                    // 的区别正好在尾部，砍完两个驱动在检测提示里长得一样
                    label = dl.fileName.substringBeforeLast('.'),
                    packagePath = dl.filePath,
                )
            }
    }

    // 指南入口在顶栏（右上角图标），这里响应信号打开
    LaunchedEffect(guideSignal) {
        if (guideSignal > 0) showDeviceGuide = true
    }

    if (showDeviceGuide) {
        DeviceGuideDialog(
            onDismiss = { showDeviceGuide = false },
            deviceInfo = deviceInfo,
            vulkanPayload = vulkanPayload,
            benchmarkResult = benchmarkResult
        )
    }

    LaunchedEffect(Unit) {
        vulkanLoading = true
        vulkanError = null
        try {
            val payload = withContext(Dispatchers.IO) {
                NativeVulkanBridge.collectSystemVulkanInfo()
            }
            vulkanPayload = payload
            if (!payload.success) {
                vulkanError = payload.errorMessage ?: payload.errorCode
            }
        } catch (e: Exception) {
            vulkanError = e.message ?: "unknown error"
        } finally {
            vulkanLoading = false
        }
    }

    val driverRoute = deviceInfo?.let { recommendDriverRoute(it) }
    // 设备页子分区：0 概览 / 1 性能 / 2 实测 / 3 资讯
    // 单页原来把所有模块竖排（含底部新闻折叠区）要滑 6~8 屏，重排成一屏一个子分区。
    var deviceTab by remember { mutableStateOf(0) }
    val deviceListState = rememberLazyListState()
    val deviceScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 子分区条固定在列表上方：滚动时始终可见，切分区即换内容，不用一路滑
        // 「资讯」已上移为一级分区，这里只剩设备本身的三段
        SectionTabs(
            tabs = listOf(
                appString(R.string.device_tab_overview),
                appString(R.string.device_tab_perf),
                appString(R.string.device_tab_playable),
            ),
            selected = deviceTab,
            onSelect = { index ->
                if (index != deviceTab) {
                    deviceTab = index
                    deviceScope.launch { deviceListState.scrollToItem(0) }
                }
            },
        )
        LazyColumn(
            state = deviceListState,
            modifier = Modifier.fillMaxSize(),
            // 底部留出固定操作条高度，否则最后一条会被压住
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 结论在参数之前：用户要的是判断，不是跑分
            if (deviceTab == 0) item(key = "device_conclusion") {
                DeviceConclusionBlock(
                    deviceInfo = deviceInfo,
                    isLoading = isLoading,
                    route = driverRoute,
                )
            }

            if (deviceTab == 0 && !isLoading && deviceInfo != null) {
                item(key = "emuready_compat") {
                    EmuReadyCard(deviceInfo = deviceInfo)
                }
                item(key = "driver_route") {
                    DriverRecommendationCard(
                        deviceInfo = deviceInfo,
                        onNavigateToDrivers = onNavigateToDrivers,
                        onOpenDriverFiles = onOpenDriverFiles
                    )
                }
                item(key = "soc_knowledge") {
                    SocKnowledgeCard(
                        context = LocalContext.current,
                        gpuModel = deviceInfo.gpuModel,
                        socHint = deviceInfo.socName,
                        gpuIsAdreno = deviceInfo.gpuVendor == GpuVendor.ADRENO
                    )
                }
            }

            if (deviceTab == 1 && !isLoading && deviceInfo != null) {
                item(key = "bench_engine") {
                    BenchEngineCard(context = LocalContext.current, gpuModel = deviceInfo.gpuModel)
                }
            }

        if (deviceTab == 1) {
        item(key = "vulkan_header") {
            SectionHeader(appString(R.string.vulkan_title))
        }

        if (vulkanLoading) {
            item(key = "vulkan_loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(appString(R.string.vulkan_collecting))
                    }
                }
            }
        } else if (vulkanError != null) {
            item(key = "vulkan_error") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.vulkan_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vulkanError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            val vp = vulkanPayload
            if (vp != null && vp.devices.isNotEmpty()) {
                val device = vp.devices[0]

            item(key = "vulkan_overview") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            device.deviceName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        InfoRow("Vulkan API", device.apiVersion)
                        InfoRow(appString(R.string.meta_driver_version), device.driverVersion)
                        if (device.driverName.isNotEmpty()) {
                            InfoRow(appString(R.string.vulkan_driver_name), device.driverName)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("关键特性", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        VULKAN_KEY_FEATURES.forEach { (key, hint) ->
                            VulkanKeyFeatureRow(
                                enabled = device.features2[key] == true,
                                label = key,
                                hint = hint
                            )
                        }
                        if (device.features2.isEmpty()) {
                            Text("本机/驱动未上报 Vulkan 1.2+ 特性（旧 ROM 或旧驱动）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item(key = "vulkan_toggle") {
                OutlinedButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showDetails) appString(R.string.vulkan_hide_details) else appString(R.string.vulkan_view_details))
                }
            }

            if (showDetails) {
                item(key = "vulkan_basic") {
                    VulkanDetailCard(
                        title = appString(R.string.vulkan_basic_info),
                        content = {
                            Text("类型: ${device.deviceType}  ·  Vendor: 0x${"%04X".format(device.vendorId)}  ·  Device: 0x${"%04X".format(device.deviceId)}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            if (device.driverInfo.isNotEmpty()) {
                                InfoRow(appString(R.string.vulkan_driver_info), device.driverInfo)
                            }
                            if (device.pipelineCacheUUID.isNotEmpty()) {
                                InfoRow(appString(R.string.vulkan_driver_fingerprint), device.pipelineCacheUUID)
                            }
                            InfoRow(appString(R.string.vulkan_formats), device.formats.size.toString())
                            if (device.sparseProperties.isNotEmpty()) {
                                val sparseOn = device.sparseProperties.count { it.value }
                                InfoRow(
                                    appString(R.string.vulkan_sparse_support),
                                    "$sparseOn/${device.sparseProperties.size}"
                                )
                            }
                            InfoRow(appString(R.string.vulkan_extensions), device.extensions.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_types), device.memoryTypes.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_heaps), device.memoryHeaps.size.toString())
                            InfoRow(appString(R.string.vulkan_queue_families), device.queueFamilies.size.toString())
                        }
                    )
                }

                item(key = "vulkan_key_exts") {
                    val extSet = device.extensions.map { it.name }.toSet()
                    val hitCount = CRITICAL_DXVK_EXTS.count { it in extSet }
                    VulkanDetailCard(
                        title = "关键扩展覆盖（DXVK/VKD3D）${hitCount}/${CRITICAL_DXVK_EXTS.size}",
                        content = {
                            CRITICAL_DXVK_EXTS.forEach { name ->
                                VulkanKeyExtensionRow(
                                    name = name,
                                    present = name in extSet,
                                    hint = VULKAN_KEY_EXT_HINTS[name] ?: ""
                                )
                            }
                        }
                    )
                }

                item(key = "vulkan_features2") {
                    val f2 = device.features2
                    VulkanDetailCard(
                        title = "Vulkan 1.2/1.3 特性（${f2.values.count { it }}/${f2.size}）",
                        content = {
                            if (f2.isEmpty()) {
                                Text("未上报（旧 ROM/驱动未导出 vkGetPhysicalDeviceFeatures2）", style = MaterialTheme.typography.bodySmall)
                            } else {
                                f2.toSortedMap().forEach { (key, value) ->
                                    VulkanKeyFeatureRow(enabled = value, label = key, hint = "")
                                }
                            }
                        }
                    )
                }

                item(key = "vulkan_formats") {
                    VulkanDetailCard(
                        title = "格式支持矩阵（${device.formats.size} 关键格式）",
                        content = {
                            if (device.formats.isEmpty()) {
                                Text("未采集（驱动未导出 vkGetPhysicalDeviceFormatProperties）", style = MaterialTheme.typography.bodySmall)
                            } else {
                                if (!device.formatFlags2) {
                                    Text(
                                        "驱动未提供 VkFormatProperties3（Vulkan 1.3 / VK_KHR_format_feature_flags2）：\n下列仅含 32 位特性位，高位特性显示为「—」不代表设备真的不支持。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                device.formats.forEach { fmt ->
                                    // 有 64 位值就用 64 位（VK_FORMAT_FEATURE_2_* 的高位标志），
                                    // 否则退回 32 位值，保证「不支持」与「读不出」可区分
                                    val effective = fmt.optimalFeatures64
                                        ?: fmt.optimalTilingFeatures.toULong()
                                    val ok = effective != 0uL
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                if (ok) "✓" else "—",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${fmt.format}  [0x${effective.toString(16)}]",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                item(key = "vulkan_features") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_features)}（${device.features.filter { it.value }.size}/${device.features.size}）",
                        content = {
                            device.features.filter { it.value }.keys.sorted().forEach { feature ->
                                Text("• $feature", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_extensions") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_extension_list)}（${device.extensions.size}）",
                        content = {
                            device.extensions.sortedBy { it.name }.forEach { ext ->
                                Text("• ${ext.name} (v${ext.specVersion})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_memory") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_memory_heaps)}（${device.memoryHeaps.size}）",
                        content = {
                            device.memoryHeaps.forEachIndexed { index, heap ->
                                val sizeMB = heap.size / (1024 * 1024)
                                Text("• ${appString(R.string.vulkan_heap, index)}: ${sizeMB}MB  [${heap.flags}]", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("${appString(R.string.vulkan_memory_types)}（${device.memoryTypes.size}）：", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            device.memoryTypes.forEachIndexed { index, mt ->
                                Text("• ${appString(R.string.vulkan_memory_type, index)}: ${appString(R.string.vulkan_heap, mt.heapIndex)}  [${mt.propertyFlags}]", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_queues") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_queue_families)}（${device.queueFamilies.size}）",
                        content = {
                            device.queueFamilies.forEachIndexed { index, qf ->
                                Text("• ${appString(R.string.vulkan_queue_family, index)}: ${appString(R.string.vulkan_queues_count, qf.queueCount)}  [${qf.queueFlags}]", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_limits") {
                    VulkanDetailCard(
                        title = appString(R.string.vulkan_key_limits),
                        content = {
                            val keyLimits = listOf(
                                "maxImageDimension2D", "maxImageDimension3D", "maxImageDimensionCube",
                                "maxImageArrayLayers", "maxUniformBufferRange", "maxStorageBufferRange",
                                "maxMemoryAllocationCount", "maxBoundDescriptorSets",
                                "maxVertexInputAttributes", "maxFragmentOutputAttachments",
                                "maxComputeSharedMemorySize", "maxComputeWorkGroupInvocations",
                                "maxViewports", "maxColorAttachments", "maxSamplerAnisotropy",
                                "timestampPeriod", "maxClipDistances", "maxCullDistances"
                            )
                            keyLimits.forEach { key ->
                                device.limits[key]?.let { value ->
                                    InfoRow(key, value.toString())
                                }
                            }
                        }
                    )
                }
            }
            }
        }

        // GPU 跑分并入「实测」子分区的驱动实测卡：同一份驱动、同一次测试里
        // 既出图又出分，不再单独占一个入口（原来三个入口各自跑一遍，用户不知道点哪个）。
        }

        // 渲染实测（自研内核）：直接出图，而不是只给一行 FPS 文字
        // 有已下载驱动时可在卡片内切换「系统驱动 / 指定驱动」，实现换驱动实测
        if (deviceTab == 1) item(key = "driver_render_check") {
            DriverRenderCheckCard(
                drivers = driverTargets,
                onBenchResult = { benchmarkResult = it },
            )
        }

        // ── 已下载驱动检测：解压 .zip → dlopen .so → 采集 Vulkan 能力 → 与系统驱动对比 ──
        if (deviceTab == 1) item(key = "driver_detect_header") {
            SectionHeader(appString(R.string.driver_detection_title))
        }

        if (deviceTab == 1) item(key = "driver_detect_list") {
            DriverDetectionList(
                systemDevice = vulkanPayload?.devices?.firstOrNull()
            )
        }

        // 可玩库：把「本机芯片」换算成「哪些平台跑得动」。
        // 放第三个子分区而不是塞进性能页：性能页回答「这台有多强」，
        // 可玩库回答「所以能玩什么」，后者才是用户打开设备页的动机。
        if (deviceTab == 2) item(key = "playable_library") {
            PlayableLibrarySection(
                entries = entries,
                onOpenPlatform = { onOpenPlatform?.invoke(it) },
                onOpenDrivers = onNavigateToDrivers
            )
        }
        }
        }

        // 底部固定操作条：详情页的「下一步」只放一个主按钮
        if (!isLoading && deviceInfo != null) {
            DetailActionBar(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { onNavigateToDrivers?.invoke() },
                    enabled = onNavigateToDrivers != null,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.goto_driver_region))
                }
            }
        }
    }
}

/** EmuReady 兼容性卡片：本地目录精确匹配本机型号 → 按 deviceId 查各平台兼容得分（0-100）。
 *
 *  旧实现直接拿"品牌+型号"联网模糊搜，上游按包含匹配返回、首条常是别的机型
 *  （搜 Xiaomi 15 给 Xiaomi 15T Pro），SoC 兜底也从未命中，所以卡片长期空着。
 *  现在先在 assets 目录里匹配型号，命中才请求；本机未收录时用同 SoC 代表机型参考，
 *  并明确标注"同芯片参考"，不冒充本机实测。 */
@Composable
private fun EmuReadyCard(deviceInfo: DeviceInfo) {
    val context = LocalContext.current
    var compat by remember { mutableStateOf<EmuReadyCompat?>(null) }
    var match by remember { mutableStateOf<EmuReadyMatch?>(null) }
    var socCompat by remember { mutableStateOf<SocCompat?>(null) }
    // 本地 SoC 表是毫秒级同步读取：先出内容，再让网络结果覆盖，
    // 避免整张卡先转圈几秒（用户之前的体感是"刷新很久才出来"）
    var loading by remember { mutableStateOf(true) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo.deviceBrand, deviceInfo.deviceModel, deviceInfo.socName) {
        loading = true
        socCompat = withContext(Dispatchers.IO) {
            EmuReadySocStore.find(context, deviceInfo.socName)
        }
        if (socCompat != null) loading = false

        val marketing = deviceMarketingName(deviceInfo.deviceModel, context)
        val models = (EmuReadyStore.systemMarketNames() + listOf(marketing, deviceInfo.deviceModel))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val hit = withContext(Dispatchers.IO) {
            EmuReadyStore.match(context, deviceInfo.deviceBrand, models, deviceInfo.socName)
        }
        match = hit
        compat = if (hit != null) {
            withContext(Dispatchers.IO) { fetchEmuReadyCompatibility(hit.device) }
        } else null
        loading = false
    }

    // 展示口径：本机有报告 → 本机；否则用同 SoC 聚合表（不再引导去官网）
    // 快照：下方 when 里不再二次读状态（后台协程可能在两次读取间把它置空，!! 会 NPE）
    val compatSnapshot = compat
    val socCompatSnapshot = socCompat
    val matchSnapshot = match
    val deviceSystems = compatSnapshot?.systems.orEmpty()
    val socSystems = socCompatSnapshot?.systems.orEmpty()
    val useDevice = deviceSystems.isNotEmpty()
    val rows = if (useDevice) deviceSystems.size else socSystems.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appString(R.string.emuready_title), style = MaterialTheme.typography.titleSmall)
                    val deviceLine = when {
                        loading && rows == 0 -> appString(R.string.emuready_loading)
                        useDevice -> compatSnapshot?.let { "${it.brandName} ${it.modelName} · ${it.socName}" }
                            ?: appString(R.string.emuready_no_data)
                        socCompatSnapshot != null -> {
                            val n = socCompatSnapshot.devices.size.coerceAtLeast(1)
                            appString(R.string.emuready_soc_line, socCompatSnapshot.soc, n)
                        }
                        matchSnapshot != null -> "${matchSnapshot.device.brandName} ${matchSnapshot.device.modelName} · ${appString(R.string.emuready_no_report)}"
                        else -> appString(R.string.emuready_no_data)
                    }
                    Text(
                        deviceLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 数据口径徽章：本机实测 / 同芯片机型聚合，别让用户误以为都是本机数据
            Spacer(Modifier.height(6.dp))
            val (badge, tint) = when {
                useDevice -> appString(R.string.emuready_badge_device) to MaterialTheme.colorScheme.primary
                socCompatSnapshot != null -> appString(R.string.emuready_badge_soc) to MaterialTheme.colorScheme.secondary
                else -> appString(R.string.emuready_badge_none) to MaterialTheme.colorScheme.outline
            }
            Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.14f)) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (useDevice) {
                val all = deviceSystems
                val shown = if (showAll) all else all.take(8)
                Spacer(Modifier.height(8.dp))
                shown.forEachIndexed { idx, s ->
                    if (idx > 0) HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    CompatRow(
                        name = emuSystemZh(s.name, ""),
                        emulator = s.bestEmulator,
                        score = s.score,
                        confidence = s.confidence,
                        source = s.dataSource,
                        listings = s.totalListings
                    )
                }
                if (all.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.emuready_expand_more, all.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (socSystems.isNotEmpty()) {
                val shown = if (showAll) socSystems else socSystems.take(8)
                Spacer(Modifier.height(8.dp))
                shown.forEachIndexed { idx, s ->
                    if (idx > 0) HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    CompatRow(
                        name = emuSystemZh(s.name, s.key),
                        emulator = s.bestEmulator,
                        score = s.score,
                        confidence = s.confidence,
                        source = s.dataSource,
                        listings = s.listings
                    )
                }
                if (socSystems.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.emuready_expand_more, socSystems.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    appString(R.string.emuready_source_note, EmuReadySocStore.dataGeneratedAt().take(10)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** EmuReady 平台名的中文对照：接口只给英文系统名，直接摆英文用户读不懂 */
internal fun emuSystemZh(name: String, key: String): String {
    val k = key.ifBlank { name.lowercase() }
    val zh = when {
        // switch2 要在 switch 之前判：contains("switch") 会把 NS2 也吃掉
        k.contains("switch_2") || k.contains("switch2") || k.contains("switch 2") -> "Switch 2"
        k.contains("switch") -> "Switch"
        k.contains("windows") -> "Windows (Winlator)"
        k.contains("playstation_2") || k.contains("ps2") -> "PS2"
        k.contains("playstation_3") || k.contains("ps3") -> "PS3"
        k.contains("playstation_vita") || k.contains("vita") -> "PS Vita"
        k.contains("playstation_portable") || k.contains("psp") -> "PSP"
        k.contains("playstation") || k.contains("ps1") -> "PS1"
        k.contains("gamecube") -> "GameCube"
        k.contains("wii_u") -> "Wii U"
        k.contains("wii") -> "Wii"
        k.contains("nintendo_3ds") || k.contains("3ds") -> "3DS"
        k.contains("nintendo_ds") || k.contains("nds") -> "NDS"
        k.contains("nintendo_64") || k.contains("n64") -> "N64"
        k.contains("snes") || k.contains("super_nintendo") -> "SFC"
        k.contains("nes") -> "FC"
        k.contains("game_boy_advance") || k.contains("gba") -> "GBA"
        k.contains("game_boy") -> "GB"
        k.contains("xbox_360") -> "Xbox 360"
        k.contains("xbox") -> "Xbox"
        k.contains("dreamcast") -> "Dreamcast"
        k.contains("saturn") -> "Saturn"
        k.contains("arcade") -> "街机"
        k.contains("apple_ios") || k.contains("ios") -> "iOS"
        k.contains("android") -> "Android"
        k.contains("linux") -> "Linux"
        else -> name
    }
    return zh
}

@Composable
internal fun CompatRow(
    name: String,
    emulator: String,
    score: Int,
    confidence: String,
    source: String,
    listings: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            // 这里是兼容报告的行，数据源里设备名带括号后缀的很多
            // （`Nintendo Switch Lite (Modchipped, HWFLY RP2040)`），单行放不下就只剩前半截，
            // 而这一行点不开、没有别的出口，所以给两行。
            LongFieldText(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val conf = when (confidence) {
                "high" -> appString(R.string.emuready_conf_high)
                "medium" -> appString(R.string.emuready_conf_mid)
                else -> appString(R.string.emuready_conf_low)
            }
            val src = if (source == "soc") appString(R.string.emuready_src_soc) else appString(R.string.emuready_src_device)
            val parts = listOfNotNull(
                emulator.takeIf { it.isNotBlank() }?.let { appString(R.string.emuready_best_emu, it) },
                conf,
                src,
                if (listings > 0) appString(R.string.emuready_listings, listings) else null
            )
            Text(
                parts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        val scoreColor = when {
            score >= 80 -> Color(0xFF2E7D32)
            score >= 60 -> Color(0xFFF9A825)
            else -> Color(0xFFC62828)
        }
        // 低置信（样本少）用描边样式：同样的 90 分，12 条报告和 200 条报告不该长得一样「实」。
        // 分数为事实不改，改的是它看起来有多确定。
        val lowSample = confidence != "high" && confidence != "medium"
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (lowSample) scoreColor.copy(alpha = 0.12f) else scoreColor,
            border = if (lowSample) BorderStroke(1.dp, scoreColor.copy(alpha = 0.7f)) else null
        ) {
            Text(
                "$score",
                style = MaterialTheme.typography.labelLarge,
                color = if (lowSample) scoreColor else Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * GPU 性能跑分卡片：触发 native Vulkan 带宽基准，展示总分 + fill/copy 带宽。
 *
 * 修改原因：用户要求实测跑分，对比不同驱动在当前设备上的性能差异。
 * 影响范围：设备 Tab 新增卡片，不影响其他页面。
 * 回滚方法：删除本函数及 DeviceScreen 中 benchmark 相关 item/状态即可。
 */


/** 计算吞吐数值 + 进度条（满格 1500 GFLOPS，对应 Adreno 8 系 compute 上限）。 */
@Composable
private fun ComputeRow(label: String, gflops: Double) {
    val fraction = (gflops / 1500.0).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (gflops > 0) String.format("%.1f GFLOPS", gflops) else "不可用",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Fusion.SurfaceAlt)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** CPU 基准数值 + 进度条（整数满格 20000 MIPS / 浮点满格 8000 MFLOPS，对应 8 核旗舰）。 */
@Composable
private fun CpuRow(label: String, value: Double, unit: String, threads: Int) {
    val maxVal = if (unit == "MIPS") 20000.0 else 8000.0
    val fraction = (value / maxVal).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value > 0) String.format("%.0f %s × %d 线程", value, unit, threads) else "不可用",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Fusion.SurfaceAlt)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 带宽数值 + 进度条（满格 64 GB/s，对应 Adreno 8 系带宽量级，低端机也能看出比例）。 */
@Composable
private fun BandwidthRow(label: String, valueGBs: Double) {
    val fraction = (valueGBs / 64.0).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format("%.2f GB/s", valueGBs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Fusion.SurfaceAlt)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
