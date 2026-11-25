package com.emubox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.emubox.app.ui.theme.Fusion
import java.io.File
import java.util.*

@Composable
internal fun DriverDetectionList(
    systemDevice: VulkanDeviceInfo?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extractFailedMsg = appString(R.string.extract_failed)
    var detectionResults by remember { mutableStateOf<Map<String, VulkanInfoPayload>>(emptyMap()) }
    var detectingId by remember { mutableStateOf<String?>(null)
    }
    // 驱动包 meta.json 解析结果（进页面自动解析，无需用户点击）
    var driverMetas by remember { mutableStateOf<Map<String, DriverMetaParser.DriverMeta?>>(emptyMap()) }
    // .so 是否导出 vkGetInstanceProcAddr（静态 ELF 检查，决定能否实测跑分）
    var exportFlags by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    // 实测跑分结果（fork 子进程加载驱动，崩溃隔离）
    var benchmarkResults by remember { mutableStateOf<Map<String, BenchmarkResult>>(emptyMap()) }
    var benchmarkingId by remember { mutableStateOf<String?>(null) }
    // AdrenoTools 实测（M4）：以模拟器同款加载路径（namespace 绕过+GPU 映射导入）
    // 加载 turnip/panvk，采集实例/设备扩展并跑分——替代裸 dlopen（App 内必然失败）。
    var adrenotoolsResults by remember { mutableStateOf<Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>>(emptyMap()) }
    var renderResults by remember { mutableStateOf<Map<String, org.json.JSONObject>>(emptyMap()) }
    var adrenotoolsTestingId by remember { mutableStateOf<String?>(null) }
    // 双选实测对比：勾选两个已下载驱动，AdrenoTools 并排实测（扩展/设备/跑分）
    val compareSelection = remember { mutableStateListOf<String>() }

    val downloadedDrivers = DownloadsManager.completedDownloads.filter { download ->
        val isDriverPackage = download.fileName.endsWith(".zip", ignoreCase = true) ||
                download.fileName.endsWith(".adpkg", ignoreCase = true)
        if (!isDriverPackage) return@filter false
        // 优先按下载目录分类（Drivers/），旧记录无 subPath 时回退关键词匹配
        if (download.subPath.isNotBlank()) {
            download.subPath.startsWith("Drivers", ignoreCase = true)
        } else {
            listOf(
                "turnip", "qualcomm", "adreno", "mali", "panvk", "driver", "vulkan", "mesa"
            ).any { download.fileName.contains(it, ignoreCase = true) }
        }
    }

    if (downloadedDrivers.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    appString(R.string.no_downloaded_drivers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    appString(R.string.no_downloaded_drivers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // 自动解析所有已下载驱动包的 meta.json（轻量，只读 zip 内小文件，不解压 .so）
    LaunchedEffect(downloadedDrivers.map { it.id }.toSet()) {
        val pending = downloadedDrivers.filter { !driverMetas.containsKey(it.id) }
        if (pending.isEmpty()) return@LaunchedEffect
        val parsed = withContext(Dispatchers.IO) {
            pending.associate { dl ->
                dl.id to runCatching { DriverMetaParser.parse(context, dl.filePath) }.getOrNull()
            }
        }
        driverMetas = driverMetas + parsed
    }

    // 解压驱动包拿 .so（本地路径或 content:// 均支持；已提取则直接复用）
    val extractSoFor: (String, String) -> File? = { id, filePath ->
        runCatching {
            val existing = DriverExtractor.getSoFile(context, id)
            if (existing != null && existing.exists()) existing
            else {
                val uri = if (filePath.startsWith("content://")) Uri.parse(filePath)
                          else Uri.fromFile(File(filePath))
                DriverExtractor.extract(context, uri, id)
            }
        }.getOrNull()
    }

    // AdrenoTools 实测目录：tmpLibDir（memfd 不可用时 patched lib 落地，App 私有可写）
    // + hookLibDir（必须 = nativeLibraryDir，hook .so 随 APK 解压于此）。
    // 注意：adrenotools_open_libvulkan 内部按 "dir + 文件名" 直接拼接 stat，
    // 目录必须以 "/" 结尾，否则拼接出非法路径，加载必失败（ADRENOTOOLS_OPEN_FAILED）。
    val adrenotoolsDirs = run {
        val tmp = File(context.filesDir, "adrenotools_tmp").apply { mkdirs() }
        (tmp.absolutePath.ensureTrailingSlash()) to context.applicationInfo.nativeLibraryDir
    }

    // AdrenoTools 实测：分阶段独立进程（collect → benchmark → render）。
    // 每个阶段一个全新 :drivertest 进程、进程内只加载一次驱动（vktest 同构），
    // 规避同进程重复 mapping import 导致 turnip GSL 状态冲突崩溃（STAGE_CRASHED 根因）。
    // 单阶段崩溃只丢该阶段结果，其余阶段照常展示。
    fun startStageService(resultId: String, stage: String, dir: String, name: String, tmp: String, hook: String) {
        DriverTestService.resultFile(context, resultId).delete()
        // 启动标记只由 DriverTestService 在 onStartCommand 里写，主进程不预写。
        // 以前这里先 writeText("1") 再 startService：服务有没有起来标记都在，
        // 于是「服务被 ROM 拦下 / 后台限制 / 参数异常」统统被判成「驱动崩溃」，
        // SERVICE_NOT_STARTED 永远不可达。这里只清理上一轮残留。
        File(context.filesDir, "driver_test_${resultId}.started").delete()
        val intent = android.content.Intent(context, DriverTestService::class.java).apply {
            putExtra("driverDir", dir)
            putExtra("driverName", name)
            putExtra("tmpDir", tmp)
            putExtra("hookLibDir", hook)
            putExtra("resultId", resultId)
            putExtra("stage", stage)
        }
        context.startService(intent)
    }

    // 崩溃步骤日志（native 每步 append）：进程被杀后主进程读取，定位崩溃到具体步骤
    fun stepLogTail(resultId: String, lines: Int = 12): String {
        return runCatching {
            val f = File(context.filesDir, "driver_test_${resultId}.step")
            if (f.exists()) f.readLines().takeLast(lines).joinToString(" > ") else ""
        }.getOrNull().orEmpty()
    }

    // 轮询单个阶段结果文件（25s 窗口）。返回结果 JSON，超时/崩溃返回 null。
    // suspend + delay：三阶段串行最长 75 秒，Thread.sleep 会占死一个 IO 线程且不响应取消，
    // 用户离开页面后仍会把整段跑完。
    suspend fun pollStageResult(resultId: String): org.json.JSONObject? {
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            val rf = DriverTestService.resultFile(context, resultId)
            if (rf.exists()) {
                val raw = runCatching { rf.readText() }.getOrNull()
                if (raw != null && raw.isNotBlank()) {
                    val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
                    if (json != null) {
                        rf.delete()
                        return json
                    }
                }
            }
            delay(300)
        }
        return null
    }

    // 第三方运行时已退役：仅清理老版本解压残留，驱动加载回退内置路径
    fun ensureExternalRuntime() = RuntimeBootstrap.retireLegacy(context)

    val testWithAdrenoTools: (String, String) -> Unit = { id, filePath ->
        adrenotoolsTestingId = id
        ensureExternalRuntime()
        scope.launch(Dispatchers.IO) {
            try {
                val soFile = extractSoFor(id, filePath)
                if (soFile == null) {
                    val fail = VulkanInfoPayload(
                        success = false, errorCode = "EXTRACT_FAILED",
                        errorMessage = "驱动包解压失败，未找到 .so", loader = "",
                        instanceExtCount = 0, instanceExtensions = emptyList(),
                        deviceCount = 0, devices = emptyList()
                    )
                    detectionResults = detectionResults + (id to fail)
                    adrenotoolsTestingId = null
                } else {
                    val (tmpDir, hookLibDir) = adrenotoolsDirs
                    val dir = (soFile.parent ?: soFile.absolutePath).ensureTrailingSlash()
                    val name = soFile.name

                    // 阶段 1 · 采集（全新进程，单次加载）
                    startStageService(id, "collect", dir, name, tmpDir, hookLibDir)
                    val collectJson = pollStageResult(id)
                    if (collectJson == null) {
                        val cause = classifyNoResult(context, "加载", id)
                        val steps = stepLogTail(id)
                        if (cause.timedOut) stopDriverTestService(context)
                        detectionResults = detectionResults + (id to VulkanInfoPayload(
                            success = false,
                            errorCode = cause.code,
                            errorMessage = cause.message +
                                (if (steps.isNotEmpty()) "\n${if (cause.crashed) "崩溃步骤" else "最后步骤"}：$steps" else ""),
                            loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
                            deviceCount = 0, devices = emptyList()
                        ))
                        adrenotoolsTestingId = null
                        return@launch
                    }
                    val payload = VulkanInfoPayload.fromJson(collectJson.getJSONObject("collect").toString())
                    detectionResults = detectionResults + (id to payload)

                    // 阶段 2 · 跑分（独立进程，单次加载）
                    val benchId = "${id}_b"
                    startStageService(benchId, "benchmark", dir, name, tmpDir, hookLibDir)
                    val benchJson = pollStageResult(benchId)
                    if (benchJson != null) {
                        val br = BenchmarkResult.fromJson(benchJson.getJSONObject("benchmark").toString())
                        adrenotoolsResults = adrenotoolsResults + (id to (payload to br))

                        // 阶段 3 · 渲染（独立进程，单次加载）
                        val renderId = "${id}_r"
                        startStageService(renderId, "render", dir, name, tmpDir, hookLibDir)
                        val renderJson = pollStageResult(renderId)
                        val renderSteps = stepLogTail(renderId)
                        renderResults = renderResults + (id to (renderJson?.getJSONObject("render")
                            ?: run {
                                // 以前这里不看 .started 直接写「进程崩溃」——渲染阶段慢一点
                                // 就被判成驱动不兼容，和加载阶段是同一个错。
                                val cause = classifyNoResult(context, "渲染", renderId)
                                if (cause.timedOut) stopDriverTestService(context)
                                org.json.JSONObject().put("success", false).put("errorCode", cause.code)
                                    .put("errorMessage", cause.message + "，跑分结果已保留" +
                                        (if (renderSteps.isNotEmpty())
                                            "\n${if (cause.crashed) "崩溃步骤" else "最后步骤"}：$renderSteps" else ""))
                            }))
                    } else {
                        val cause = classifyNoResult(context, "跑分", benchId)
                        val steps = stepLogTail(benchId)
                        if (cause.timedOut) stopDriverTestService(context)
                        adrenotoolsResults = adrenotoolsResults + (id to (payload to BenchmarkResult(
                            success = false,
                            errorCode = cause.code,
                            errorMessage = cause.message +
                                (if (steps.isNotEmpty()) "\n${if (cause.crashed) "崩溃步骤" else "最后步骤"}：$steps" else ""),
                            deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                            computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                            totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                        )))
                    }
                    adrenotoolsTestingId = null
                }
            } catch (e: Exception) {
                detectionResults = detectionResults + (id to VulkanInfoPayload(
                    success = false, errorCode = "ADT_EXCEPTION",
                    errorMessage = e.message ?: "unknown", loader = "",
                    instanceExtCount = 0, instanceExtensions = emptyList(),
                    deviceCount = 0, devices = emptyList()
                ))
                adrenotoolsTestingId = null
            }
        }
    }

    // 静态检查 .so 是否导出 vkGetInstanceProcAddr（懒触发，IO 线程，缓存结果）
    val checkExportable: (String, String) -> Unit = { id, filePath ->
        scope.launch(Dispatchers.IO) {
            val flag = DriverMetaParser.soExportsVkGetInstanceProcAddr(extractSoFor(id, filePath))
            exportFlags = exportFlags + (id to flag)
        }
    }

    // 实测跑分：与"驱动信息"同一条独立进程链路（service + AdrenoTools + 系统预热）。
    // 旧实现 fork 子进程裸 dlopen 驱动 .so 跑带宽：无 root 进程无法访问 GPU 节点，
    // 与 vktest 差异在缺系统 Vulkan 宿主上下文，实测必败；现统一走 service，
    // 结果文件独立命名（bench_ 前缀），不占用检测轮询位。
    val benchmarkDriver: (String, String) -> Unit = { id, filePath ->
        benchmarkingId = id
        ensureExternalRuntime()
        scope.launch(Dispatchers.IO) {
            try {
                val soFile = extractSoFor(id, filePath)
                exportFlags = exportFlags + (id to DriverMetaParser.soExportsVkGetInstanceProcAddr(soFile))
                if (soFile == null) {
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                        success = false, errorCode = "EXTRACT_FAILED",
                        errorMessage = "驱动包解压失败，未找到 .so",
                        deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                        computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                        totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                    ))
                    benchmarkingId = null
                } else {
                    val (tmpDir, hookLibDir) = adrenotoolsDirs
                    val dir = (soFile.parent ?: soFile.absolutePath).ensureTrailingSlash()
                    val benchResultId = "bench_$id"
                    DriverTestService.resultFile(context, benchResultId).delete()
                    // 同上：启动标记交给 service 写，主进程只清残留
                    val startedFile = File(context.filesDir, "driver_test_${benchResultId}.started")
                    startedFile.delete()
                    val intent = android.content.Intent(context, DriverTestService::class.java).apply {
                        putExtra("driverDir", dir)
                        putExtra("driverName", soFile.name)
                        putExtra("tmpDir", tmpDir)
                        putExtra("hookLibDir", hookLibDir)
                        putExtra("resultId", benchResultId)
                        putExtra("stage", "benchmark")
                    }
                    context.startService(intent)
                    // 轮询结果（与检测共用 25s 窗口；native 加载+渲染在 service 内完成）
                    val deadline = System.currentTimeMillis() + 25_000L
                    while (System.currentTimeMillis() < deadline) {
                        val rf = DriverTestService.resultFile(context, benchResultId)
                        if (rf.exists()) {
                            val raw = runCatching { rf.readText() }.getOrNull()
                            if (raw != null && raw.isNotBlank()) {
                                val result = runCatching { org.json.JSONObject(raw) }.getOrNull()
                                if (result != null) {
                                    // 分阶段写盘：有 benchmark 直接用；仅 collect 说明跑分/渲染阶段崩溃
                                    val brJson = result.optJSONObject("benchmark")
                                    if (brJson != null) {
                                        benchmarkResults = benchmarkResults + (id to BenchmarkResult.fromJson(brJson.toString()))
                                    } else if (result.has("collect")) {
                                        benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                                            success = false, errorCode = "STAGE_CRASHED",
                                            errorMessage = "跑分/渲染阶段进程崩溃，采集结果已保留",
                                            deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                                            computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                                            totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                                        ))
                                    } else {
                                        // 结果文件已落盘但字段不全（可能正在写）。
                                        // continue 会跳过循环末尾的 sleep，导致 25 秒满速空转读文件，
                                        // 白烧一个核；先让出这一段再继续。
                                        Thread.sleep(300)
                                        continue
                                    }
                                    rf.delete()
                                    benchmarkingId = null
                                    return@launch
                                }
                            }
                        }
                        Thread.sleep(300)
                    }
                    // 超时/崩溃判定
                    val cause = classifyNoResult(context, "跑分", benchResultId)
                    val steps = stepLogTail(benchResultId)
                    if (cause.timedOut) stopDriverTestService(context)
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                        success = false,
                        errorCode = cause.code,
                        errorMessage = cause.message +
                            (if (steps.isNotEmpty()) "\n${if (cause.crashed) "崩溃步骤" else "最后步骤"}：$steps" else ""),
                        deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                        computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                        totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                    ))
                    benchmarkingId = null
                }
            } catch (e: Exception) {
                benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                    success = false, errorCode = "BENCHMARK_EXCEPTION",
                    errorMessage = e.message ?: "unknown",
                    deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                    computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                    totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                ))
                benchmarkingId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetectionScopeNote()
        DriverDetectionCards(
            drivers = downloadedDrivers,
            context = context,
            systemDevice = systemDevice,
            detectionResults = detectionResults,
            detectingId = detectingId,
            driverMetas = driverMetas,
            exportFlags = exportFlags,
            benchmarkResults = benchmarkResults,
            benchmarkingId = benchmarkingId,
            adrenotoolsResults = adrenotoolsResults,
            renderResults = renderResults,
            adrenotoolsTestingId = adrenotoolsTestingId,
            compareSelection = compareSelection,
            testWithAdrenoTools = testWithAdrenoTools,
            benchmarkDriver = benchmarkDriver,
            checkExportable = checkExportable,
            onMetaParsed = { id, meta -> driverMetas = driverMetas + (id to meta) },
            onDetectionResult = { id, payload -> detectionResults = detectionResults + (id to payload) }
        )
        DriverComparePanel(
            drivers = downloadedDrivers,
            context = context,
            compareSelection = compareSelection,
            driverMetas = driverMetas,
            adrenotoolsResults = adrenotoolsResults,
            adrenotoolsTestingId = adrenotoolsTestingId,
            testWithAdrenoTools = testWithAdrenoTools,
            onMetaParsed = { id, meta -> driverMetas = driverMetas + (id to meta) }
        )
    }
}

@Composable
private fun DetectionScopeNote() {
    // 检测覆盖面说明：明确 AdrenoTools 实测的能力边界
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                appString(R.string.detection_coverage_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                appString(R.string.detection_coverage_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriverDetectionCards(
    drivers: List<DownloadsManager.CompletedDownload>,
    context: Context,
    systemDevice: VulkanDeviceInfo?,
    detectionResults: Map<String, VulkanInfoPayload>,
    detectingId: String?,
    driverMetas: Map<String, DriverMetaParser.DriverMeta?>,
    exportFlags: Map<String, Boolean?>,
    benchmarkResults: Map<String, BenchmarkResult>,
    benchmarkingId: String?,
    adrenotoolsResults: Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>,
    renderResults: Map<String, org.json.JSONObject>,
    adrenotoolsTestingId: String?,
    compareSelection: SnapshotStateList<String>,
    testWithAdrenoTools: (String, String) -> Unit,
    benchmarkDriver: (String, String) -> Unit,
    checkExportable: (String, String) -> Unit,
    onMetaParsed: (String, DriverMetaParser.DriverMeta?) -> Unit,
    onDetectionResult: (String, VulkanInfoPayload) -> Unit
) {
    drivers.forEach { download ->
        DriverRowCard(
            download = download,
            context = context,
            systemDevice = systemDevice,
            detectionResults = detectionResults,
            detectingId = detectingId,
            driverMetas = driverMetas,
            exportFlags = exportFlags,
            benchmarkResults = benchmarkResults,
            benchmarkingId = benchmarkingId,
            adrenotoolsResults = adrenotoolsResults,
            renderResults = renderResults,
            adrenotoolsTestingId = adrenotoolsTestingId,
            compareSelection = compareSelection,
            testWithAdrenoTools = testWithAdrenoTools,
            benchmarkDriver = benchmarkDriver,
            checkExportable = checkExportable,
            onMetaParsed = onMetaParsed,
            onDetectionResult = onDetectionResult
        )
    }
}

@Composable
private fun DriverRowCard(
    download: DownloadsManager.CompletedDownload,
    context: Context,
    systemDevice: VulkanDeviceInfo?,
    detectionResults: Map<String, VulkanInfoPayload>,
    detectingId: String?,
    driverMetas: Map<String, DriverMetaParser.DriverMeta?>,
    exportFlags: Map<String, Boolean?>,
    benchmarkResults: Map<String, BenchmarkResult>,
    benchmarkingId: String?,
    adrenotoolsResults: Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>,
    renderResults: Map<String, org.json.JSONObject>,
    adrenotoolsTestingId: String?,
    compareSelection: SnapshotStateList<String>,
    testWithAdrenoTools: (String, String) -> Unit,
    benchmarkDriver: (String, String) -> Unit,
    checkExportable: (String, String) -> Unit,
    onMetaParsed: (String, DriverMetaParser.DriverMeta?) -> Unit,
    onDetectionResult: (String, VulkanInfoPayload) -> Unit
) {
    val result = detectionResults[download.id]
    val isDetecting = detectingId == download.id
    val meta = driverMetas[download.id]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            DriverRowHeader(
                download = download,
                context = context,
                result = result,
                isDetecting = isDetecting,
                meta = meta,
                adrenotoolsTestingId = adrenotoolsTestingId,
                testWithAdrenoTools = testWithAdrenoTools,
                onMetaParsed = onMetaParsed,
                onDetectionResult = onDetectionResult
            )
            DriverMetaSection(meta)
            DriverAdrenoToolsSection(
                download = download,
                systemDevice = systemDevice,
                adrenotoolsResults = adrenotoolsResults,
                renderResults = renderResults
            )
            DriverBenchmarkSection(
                download = download,
                exportFlags = exportFlags,
                benchmarkResults = benchmarkResults,
                benchmarkingId = benchmarkingId,
                benchmarkDriver = benchmarkDriver,
                checkExportable = checkExportable
            )
            DriverDetectionResultSection(result, systemDevice)
            DriverCompareToggle(download, compareSelection)
        }
    }
}

@Composable
private fun DriverRowHeader(
    download: DownloadsManager.CompletedDownload,
    context: Context,
    result: VulkanInfoPayload?,
    isDetecting: Boolean,
    meta: DriverMetaParser.DriverMeta?,
    adrenotoolsTestingId: String?,
    testWithAdrenoTools: (String, String) -> Unit,
    onMetaParsed: (String, DriverMetaParser.DriverMeta?) -> Unit,
    onDetectionResult: (String, VulkanInfoPayload) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LongFieldText(
                download.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            Text(
                "${"%.1f".format(download.sizeBytes / 1024.0 / 1024.0)} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isDetecting || adrenotoolsTestingId == download.id) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(
                onClick = {
                    // 只读 meta.json 判定打包格式：
                    // - VENDOR_SYSTEM：厂商系统提取驱动（需 root/系统级安装），
                    //   AdrenoTools 也无法加载，保留说明提示。
                    // - ADRENOTOOLS_PACKAGE / 其他：AdrenoTools 实测——
                    //   以模拟器同款路径在 App 内加载 turnip/panvk 驱动，
                    //   采集实例/设备扩展 + 跑分（崩溃隔离在 fork 子进程）。
                    val parsed = meta
                        ?: runCatching { DriverMetaParser.parse(context, download.filePath) }.getOrNull()
                    onMetaParsed(download.id, parsed)
                    val type = parsed?.driverType()
                        ?: DriverMetaParser.DriverType.UNKNOWN
                    if (type == DriverMetaParser.DriverType.VENDOR_SYSTEM) {
                        val payload = VulkanInfoPayload(
                            success = false,
                            errorCode = "VENDOR_SYSTEM_DRIVER",
                            errorMessage = "厂商系统提取驱动（需 root/系统级安装），无法在 App 内检测，请在模拟器内加载验证",
                            loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
                            deviceCount = 0,
                            devices = emptyList()
                        )
                        onDetectionResult(download.id, payload)
                    } else {
                        testWithAdrenoTools(download.id, download.filePath)
                    }
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (result != null) appString(R.string.redetect) else appString(R.string.view_driver_info),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DriverMetaSection(meta: DriverMetaParser.DriverMeta?) {
    // meta.json 驱动信息（自动解析后直接展示，这是该驱动包的真实身份信息）
    if (meta != null && !meta.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        if (meta.name.isNotBlank())
            InfoRow(appString(R.string.meta_name), meta.name)
        if (meta.packageVersion.isNotBlank())
            InfoRow(appString(R.string.meta_package_version), meta.packageVersion)
        if (meta.driverVersion.isNotBlank())
            InfoRow(appString(R.string.meta_driver_version), meta.driverVersion)
        if (meta.vendor.isNotBlank())
            InfoRow(appString(R.string.meta_vendor), meta.vendor)
        if (meta.author.isNotBlank())
            InfoRow(appString(R.string.meta_author), meta.author)
        if (meta.minApi.isNotBlank())
            InfoRow(appString(R.string.meta_min_api), meta.minApi)
        if (meta.description.isNotBlank())
            InfoRow("驱动说明", meta.description)
        InfoRow(
            "驱动类型",
            when (meta.driverType()) {
                DriverMetaParser.DriverType.VENDOR_SYSTEM -> "厂商系统提取（需 root/系统级安装，App 内不可加载）"
                DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> "AdrenoTools 打包（模拟器注入加载，App 内以 fork 隔离实测）"
                else -> "未声明/其他（App 内按通用路径尝试）"
            }
        )
    }
}

@Composable
private fun DriverAdrenoToolsSection(
    download: DownloadsManager.CompletedDownload,
    systemDevice: VulkanDeviceInfo?,
    adrenotoolsResults: Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>,
    renderResults: Map<String, org.json.JSONObject>
) {
    // ── AdrenoTools 实测结果（M4）：实例/设备扩展、设备属性、兼容、跑分 ──
    // 加载路径与 Winlator/GameHub/Eden 一致，是当前最接近模拟器内真实
    // 行为的 App 内驱动验证方式。
    val adtTest = adrenotoolsResults[download.id]
    if (adtTest != null) {
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(6.dp))
        val (adtCollect, adtBench) = adtTest
        if (adtCollect.success) {
            Text(
                "AdrenoTools 实测",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            InfoRow("实例扩展", "${adtCollect.instanceExtCount} 个")
            val dev = adtCollect.devices.firstOrNull()
            if (dev != null) {
                InfoRow("设备", dev.deviceName)
                InfoRow("API 版本", dev.apiVersion)
                InfoRow("驱动版本", dev.driverVersion)
                InfoRow("设备扩展", "${dev.extensions.size} 个")
                // 关键扩展缺失提示：DXVK/VKD3D 转译依赖的关键扩展逐项对照
                val extNames = dev.extensions.map { it.name }
                val missingCritical = CRITICAL_DXVK_EXTS.filter { it !in extNames }
                if (missingCritical.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "缺失关键扩展 ${missingCritical.size}/${CRITICAL_DXVK_EXTS.size}（影响转译层功能）",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                missingCritical.joinToString("、"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // 兼容徽章：驱动面向 GPU 厂商 vs 当前设备 GPU 厂商
                val sysVendor = systemDevice?.vendorId
                val compat = when (dev.vendorId) {
                    sysVendor -> "与当前设备 GPU 同厂商（预计可加载）"
                    else -> "驱动面向 ${vendorName(dev.vendorId)}，本机为 ${vendorName(sysVendor)}，加载结果以实测为准"
                }
                InfoRow("GPU 兼容", compat)
            if (adtBench.success) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "综合 ${adtBench.totalScore}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        "带宽 ${"%.1f".format(adtBench.fillBandwidthGBs)} GB/s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "拷贝 ${"%.1f".format(adtBench.copyBandwidthGBs)} GB/s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(
                    "跑分失败：${adtBench.errorCode ?: "?"} ${adtBench.errorMessage ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // ── 渲染测试（vktest 同款）：离屏渲染三角形 60 帧测 FPS ──
            val renderRes = renderResults[download.id]
            if (renderRes != null) {
                Spacer(Modifier.height(6.dp))
                if (renderRes.optBoolean("success")) {
                    val mode = renderRes.optString("mode", "graphics")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "渲染测试 ${"%.1f".format(renderRes.optDouble("fps", 0.0))} FPS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        val avgMs = renderRes.optDouble("avgFrameMs", 0.0)
                        if (avgMs > 0.0) {
                            Text(
                                "帧耗时 ${"%.2f".format(avgMs)} ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val frames = renderRes.optInt("frameCount", -1)
                        if (frames > 0) {
                            Text("${frames} 帧", style = MaterialTheme.typography.bodySmall)
                        }
                        val gbs = renderRes.optDouble("copyBandwidthGBs", 0.0)
                        if (gbs > 0.0) {
                            Text(
                                "拷贝 ${"%.1f".format(gbs)} GB/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (mode == "copy-render") {
                        Text(
                            "兼容模式：fill+copy 渲染链验证（turnip graphics pipeline 编译崩溃，已绕开），读回校验 " +
                                (if (renderRes.optBoolean("readbackOk", false)) "通过" else "失败"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "离屏渲染 256×256 彩色三角形（vktest 同款图形管线，每帧同步提交）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    var renderErrExpanded by remember { mutableStateOf(false) }
                    val renderErrMsg = renderRes.optString("errorMessage", "")
                    Text(
                        "渲染测试失败：${renderRes.optString("errorCode", "?")} $renderErrMsg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (renderErrExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { renderErrExpanded = !renderErrExpanded }
                    )
                    if (!renderErrExpanded && renderErrMsg.length > 48) {
                        Text(
                            "点击展开完整内容",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { renderErrExpanded = true }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 游戏支持评估：按实测驱动类型分级（独立于跑分，实测成功即显示）
            val evalDriverType = when (dev.vendorId) {
                0x5143 -> "turnip"
                0x13B5 -> "panvk"
                else -> "system"
            }
            val evals = assessGameSupport(
                driverType = evalDriverType,
                isAdrenoDevice = (systemDevice?.vendorId ?: 0) == 0x5143,
                isMaliDevice = (systemDevice?.vendorId ?: 0) == 0x13B5,
                extensions = dev.extensions.map { it.name },
                apiVersion = dev.apiVersion
            )
            Column(Modifier.padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("游戏支持评估", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("基于驱动能力静态评估，非实测运行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                evals.forEach { e ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val color = when (e.level) {
                            "基本能跑" -> Color(0xFF2E7D32)
                            "可能能跑" -> Color(0xFFF9A825)
                            else -> Color(0xFFC62828)
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(e.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(e.level, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
                Text(
                    evals.firstOrNull()?.note ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        } else {
            // 实测不可用：驱动加载失败多为驱动与当前 GPU 不匹配或系统限制
            // （SELinux/kgsl），并非包损坏——包信息已展示，可到模拟器内验证。
            val adtErr = adtCollect.errorCode ?: "?"
            Text(
                if (adtErr == "ADRENOTOOLS_OPEN_FAILED")
                    "实测不可用：驱动加载失败（多为驱动与当前 GPU 不匹配或系统权限限制）。包信息已展示，可在对应模拟器内加载验证"
                else
                    "AdrenoTools 实测失败：$adtErr ${adtCollect.errorMessage ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriverBenchmarkSection(
    download: DownloadsManager.CompletedDownload,
    exportFlags: Map<String, Boolean?>,
    benchmarkResults: Map<String, BenchmarkResult>,
    benchmarkingId: String?,
    benchmarkDriver: (String, String) -> Unit,
    checkExportable: (String, String) -> Unit
) {
    // ── 实测：App 内加载驱动（fork 子进程隔离，崩溃只死子进程）──
    // 驱动需导出 vk_icdGetInstanceProcAddr / vkGetInstanceProcAddr（ICD 入口）；
    // 能否真正初始化 GPU 取决于设备 SELinux 是否允许 App 访问 GPU 节点。
    Spacer(Modifier.height(10.dp))
    Divider()
    Spacer(Modifier.height(6.dp))
    // 快照：map 状态在两次读取间可能被后台协程改写，先取一次
    val brForId = benchmarkResults[download.id]
    when {
        benchmarkingId == download.id -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    appString(R.string.benchmark_loading_driver),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        brForId != null -> {
            val br = brForId
            if (br.success) {
                Text(
                    "该驱动实测（${br.deviceName}）",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "fork 隔离直载驱动跑分；失败多为 SELinux 限制 GPU 节点访问",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "综合 ${br.totalScore}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        "带宽 ${"%.1f".format(br.fillBandwidthGBs)} GB/s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(
                    "实测失败：${br.errorCode ?: "?"} ${br.errorMessage ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { benchmarkDriver(download.id, download.filePath) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(appString(R.string.benchmark_rerun), style = MaterialTheme.typography.bodySmall)
            }
        }
        exportFlags[download.id] == true -> {
            Button(
                onClick = { benchmarkDriver(download.id, download.filePath) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.benchmark_run_driver), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.benchmark_isolated_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        exportFlags[download.id] == false -> {
            // 静态检查未确认 ICD 入口（自写解析器对 turnip 有误判史），
            // 不拦截实测——native AdrenoTools 加载才是权威判断。
            Button(
                onClick = { benchmarkDriver(download.id, download.filePath) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.benchmark_run_driver), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.benchmark_static_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            // 懒检查：进页面不解压大 .so，点按钮才检查 ICD 入口并实测
            TextButton(
                onClick = { checkExportable(download.id, download.filePath) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(appString(R.string.benchmark_check_export), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DriverDetectionResultSection(
    result: VulkanInfoPayload?,
    systemDevice: VulkanDeviceInfo?
) {
    if (result != null) {
        Spacer(Modifier.height(10.dp))
        if (result.errorCode == "ADRENOTOOLS_PACKAGE") {
            // 模拟器专用格式：这是正常的格式说明，不是错误，用中性信息色
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        appString(R.string.adrenotools_format_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        appString(R.string.adrenotools_format_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        } else if (!result.success) {
            val errMsg = when (result.errorCode) {
                "EXTRACT_FAILED" -> appString(R.string.detection_error_extract)
                "VENDOR_SYSTEM_DRIVER" -> (result.errorMessage
                    ?: "厂商系统提取驱动，无法在 App 内检测，请在模拟器内加载验证")

                "UNKNOWN_PACKAGE" -> (result.errorMessage
                    ?: appString(R.string.unknown_package_hint))
                "DL_OPEN_FAILED", "NO_VK_ENTRY", "CREATE_INSTANCE_FAILED",
                "ENUM_DEVICES_MISSING", "DRIVER_CRASHED" -> appString(R.string.detection_error_adrenotools)
                "ADRENOTOOLS_OPEN_FAILED" -> "实测不可用：驱动加载失败（多为驱动与当前 GPU 不匹配或系统权限限制）。包信息已展示，可在对应模拟器内加载验证"
                // 下面四个此前都没有分支，全部落到 else 靠 message 关键词碰运气：
                // ADT_EXCEPTION 会把 "Not allowed to start service..." 这类英文异常直接甩给用户。
                // 原 DETECT_EXCEPTION 分支是死代码——仓库里没有任何一处产生这个值。
                "DRIVER_CRASHED_IN_SERVICE", "STAGE_CRASHED" -> appString(R.string.detection_error_crashed)
                "SERVICE_NOT_STARTED" -> appString(R.string.detection_error_not_started)
                "DETECT_TIMEOUT" -> appString(R.string.detection_error_timeout)
                "ADT_EXCEPTION" -> appString(R.string.detection_error_exception, (result.errorMessage ?: "unknown"))
                else -> {
                    val raw = result.errorMessage ?: ""
                    if (raw.contains("not resolved", ignoreCase = true) || raw.contains("dlopen", ignoreCase = true))
                        appString(R.string.detection_error_adrenotools)
                    else
                        appString(R.string.detection_failed, raw.ifEmpty { result.errorCode ?: "" })
                }
            }
            Text(
                errMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (result.devices.isNotEmpty()) {
            val d = result.devices[0]
            Text(
                d.deviceName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            InfoRow("Vulkan API", d.apiVersion)
            InfoRow(appString(R.string.meta_driver_version), d.driverVersion)
            if (d.driverName.isNotEmpty()) InfoRow(appString(R.string.vulkan_driver_name), d.driverName)
            InfoRow(appString(R.string.vulkan_extensions), "${d.extensions.size}")
            InfoRow(appString(R.string.vulkan_memory_types), "${d.memoryTypes.size}")
            InfoRow(appString(R.string.vulkan_queue_families), "${d.queueFamilies.size}")

            if (systemDevice != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    appString(R.string.compare_system),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                val extDiff = d.extensions.size - systemDevice.extensions.size
                InfoRow(appString(R.string.api_version), "${d.apiVersion}（系统 ${systemDevice.apiVersion}）")
                InfoRow(
                    appString(R.string.vulkan_extensions),
                    "${d.extensions.size}（系统 ${systemDevice.extensions.size}，${if (extDiff > 0) "+$extDiff" else if (extDiff < 0) extDiff.toString() else appString(R.string.same)}）"
                )
                val compatible = d.deviceName == systemDevice.deviceName
                InfoRow(
                    appString(R.string.gpu_compatibility),
                    if (compatible) appString(R.string.gpu_compatible) else appString(R.string.gpu_not_compatible, d.deviceName)
                )
            }
        }
    }
}

@Composable
private fun DriverCompareToggle(
    download: DownloadsManager.CompletedDownload,
    compareSelection: SnapshotStateList<String>
) {
    // ── 双选实测对比（M4）：勾选本驱动参与 AdrenoTools 并排实测 ──
    Spacer(Modifier.height(8.dp))
    val inCompare = download.id in compareSelection
    OutlinedButton(
        onClick = {
            if (inCompare) {
                compareSelection.remove(download.id)
            } else {
                if (compareSelection.size >= 2) compareSelection.removeAt(0)
                compareSelection.add(download.id)
            }
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = if (inCompare) ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) else ButtonDefaults.outlinedButtonColors()
    ) {
        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (inCompare) "已选对比（点击取消）" else "加入对比",
            style = MaterialTheme.typography.bodySmall
        )
    }
}



@Composable
private fun DriverComparePanel(
    drivers: List<DownloadsManager.CompletedDownload>,
    context: Context,
    compareSelection: SnapshotStateList<String>,
    driverMetas: Map<String, DriverMetaParser.DriverMeta?>,
    adrenotoolsResults: Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>,
    adrenotoolsTestingId: String?,
    testWithAdrenoTools: (String, String) -> Unit,
    onMetaParsed: (String, DriverMetaParser.DriverMeta?) -> Unit
) {
    // ── AdrenoTools 双选实测对比（M4）：同机同条件并排比跑分/扩展 ──
    if (compareSelection.size == 2) {
        val idA = compareSelection[0]
        val idB = compareSelection[1]
        val dlA = drivers.firstOrNull { it.id == idA }
        val dlB = drivers.firstOrNull { it.id == idB }
        if (dlA != null && dlB != null) {
            val resA = adrenotoolsResults[idA]
            val resB = adrenotoolsResults[idB]
            // 原来在这里按 22 字砍一刀：`Turnip_Gen8_v26.0_R2_A6xx` 变成
            // `Turnip_Gen8_v26.0_R2_A`，两个驱动的区别（A6xx/A7xx）正好被砍掉。
            // CompareRow 里的 Text 本来就是自动换行，没有任何截断的必要。
            val shortName: (String) -> String = { it }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "驱动对比",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "有实测结果时并排展示采集+跑分；实测不可用时回退 meta.json 静态对比",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (resA != null && resB != null) {
                        val (cA, bA) = resA
                        val (cB, bB) = resB
                        val dA = cA.devices.firstOrNull()
                        val dB = cB.devices.firstOrNull()
                        CompareRow("驱动", shortName(dlA.fileName), shortName(dlB.fileName))
                        if (bA.success && bB.success) {
                            CompareRow("综合跑分", "${bA.totalScore}", "${bB.totalScore}")
                            CompareRow("填充带宽 GB/s", "%.1f".format(bA.fillBandwidthGBs), "%.1f".format(bB.fillBandwidthGBs))
                            CompareRow("拷贝带宽 GB/s", "%.1f".format(bA.copyBandwidthGBs), "%.1f".format(bB.copyBandwidthGBs))
                        } else {
                            CompareRow(
                                "跑分",
                                if (bA.success) "OK" else (bA.errorCode ?: "失败"),
                                if (bB.success) "OK" else (bB.errorCode ?: "失败")
                            )
                        }
                        CompareRow("实例扩展", "${cA.instanceExtCount}", "${cB.instanceExtCount}")
                        CompareRow("设备扩展", "${dA?.extensions?.size ?: 0}", "${dB?.extensions?.size ?: 0}")
                        CompareRow("关键扩展覆盖", "${"%.0f".format(criticalExtCoverage(dA?.extensions?.map { it.name } ?: emptyList()) * 100)}%", "${"%.0f".format(criticalExtCoverage(dB?.extensions?.map { it.name } ?: emptyList()) * 100)}%")
                        CompareRow("API 版本", dA?.apiVersion ?: "—", dB?.apiVersion ?: "—")
                        CompareRow("GPU", dA?.deviceName ?: "—", dB?.deviceName ?: "—")
                        val scoreA = bA.totalScore
                        val scoreB = bB.totalScore
                        if (scoreA > 0 || scoreB > 0) {
                            Spacer(Modifier.height(6.dp))
                            val winner = when {
                                scoreA > scoreB -> shortName(dlA.fileName)
                                scoreB > scoreA -> shortName(dlB.fileName)
                                else -> "持平"
                            }
                            Text(
                                "综合跑分更高：$winner",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    } else {
                        // ── meta.json 静态对比：实测未跑/失败时也有真实信息可比 ──
                        val metaA = driverMetas[idA]
                        val metaB = driverMetas[idB]
                        val typeLabel: (DriverMetaParser.DriverType) -> String = { t ->
                            when (t) {
                                DriverMetaParser.DriverType.VENDOR_SYSTEM -> "厂商系统提取"
                                DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> "AdrenoTools 打包"
                                else -> "未声明/其他"
                            }
                        }
                        CompareRow("驱动", shortName(dlA.fileName), shortName(dlB.fileName))
                        if (metaA != null && metaB != null) {
                            CompareRow("名称", metaA.name, metaB.name)
                            CompareRow("包版本", metaA.packageVersion, metaB.packageVersion)
                            CompareRow("驱动版本", metaA.driverVersion, metaB.driverVersion)
                            CompareRow("厂商", metaA.vendor, metaB.vendor)
                            CompareRow("作者", metaA.author, metaB.author)
                            CompareRow("驱动类型", typeLabel(metaA.driverType()), typeLabel(metaB.driverType()))
                        } else {
                            Text(
                                "驱动包 meta.json 信息待解析（点击下方按钮触发解析+实测）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val running = adrenotoolsTestingId != null
                        Button(
                            onClick = {
                                // 先补解析 meta（若缺），再依次实测
                                if (metaA == null) {
                                    val parsedA = runCatching { DriverMetaParser.parse(context, dlA.filePath) }.getOrNull()
                                    if (parsedA != null) onMetaParsed(idA, parsedA)
                                }
                                if (metaB == null) {
                                    val parsedB = runCatching { DriverMetaParser.parse(context, dlB.filePath) }.getOrNull()
                                    if (parsedB != null) onMetaParsed(idB, parsedB)
                                }
                                testWithAdrenoTools(idA, dlA.filePath)
                                testWithAdrenoTools(idB, dlB.filePath)
                            },
                            enabled = !running
                        ) {
                            if (running) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("实测中…", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("解析+实测对比", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "实测受 App 进程环境限制（SELinux/glibc），失败时以上 meta 静态对比仍可作为版本/作者/类型依据；性能请在模拟器内验证",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { compareSelection.clear() }) {
                        Text("清除选择", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


@Composable
internal fun InfoRow(label: String, value: String) {
    // 之前用 SpaceBetween + 都不限宽：遇到「网络 / AI:NPU: Hexagon AI Engine /
    // X75 5G Modem / …」这种长字段，Text 会一路往右画到屏幕外，被父容器直接裁掉，
    // 连省略号都没有。改成按权重分配行宽，value 右对齐、最多三行。
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f)
        )
    }
}

/** Vulkan 厂商 ID → 名称（用于驱动与设备兼容性对比）。 */
private fun vendorName(vendorId: Int?): String {
    return when (vendorId) {
        0x5143 -> "Qualcomm Adreno"
        0x13B5 -> "ARM Mali"
        0x1002 -> "AMD"
        0x10DE -> "NVIDIA"
        0x8086 -> "Intel"
        0x106B -> "Apple"
        0x1AE0 -> "Google"
        else -> "未知 (0x${vendorId?.toString(16) ?: "?"})"
    }
}

/** Vulkan 关键特性（Vulkan 1.2/1.3 高价值项）中文说明。普通档+root 档共用。 */
internal val VULKAN_KEY_FEATURES: List<Pair<String, String>> = listOf(
    "dynamicRendering" to "动态渲染（现代管线首选，DXVK 9+/Zink 依赖）",
    "timelineSemaphore" to "时间线信号量（VKD3D-Proton 常用）",
    "descriptorIndexing" to "描述符索引（现代渲染必需）",
    "synchronization2" to "同步2 API（新驱动首选同步方式）",
    "bufferDeviceAddress" to "缓冲设备地址（VKD3D/光追相关）",
    "shaderFloat16" to "FP16 着色器（DXVK 性能优化项）",
    "pipelineCreationCacheControl" to "管线缓存控制（重复构建提速）",
    "inlineUniformBlock" to "内联统一块（UBO 访问加速）"
)

/** 关键特性徽章行：✓ 高亮 / ✗ 灰显，右侧附用途说明。 */
@Composable
internal fun VulkanKeyFeatureRow(enabled: Boolean, label: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (enabled) Fusion.Success else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                if (enabled) "✓" else "✗",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Fusion.OnSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 关键扩展覆盖矩阵：DXVK/VKD3D 转译依赖的关键扩展命中情况。 */
@Composable
internal fun VulkanKeyExtensionRow(name: String, present: Boolean, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (present) Fusion.Success else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                if (present) "✓" else "✗",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (present) Fusion.OnSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 关键扩展用途说明（DXVK/VKD3D 视角）。 */
internal val VULKAN_KEY_EXT_HINTS = mapOf(
    "VK_KHR_swapchain" to "交换链：窗口呈现核心",
    "VK_KHR_maintenance1" to "命令缓冲维护（基础兼容）",
    "VK_KHR_maintenance2" to "维护2（几何着色器点大小等）",
    "VK_KHR_maintenance3" to "维护3（描述符集上限）",
    "VK_KHR_timeline_semaphore" to "时间线信号量（VKD3D 依赖）",
    "VK_KHR_dynamic_rendering" to "动态渲染（DXVK 9+ 依赖）",
    "VK_EXT_memory_budget" to "内存预算（进程内存管理）",
    "VK_KHR_descriptor_indexing" to "描述符索引（现代绑定）",
    "VK_KHR_16bit_storage" to "16 位存储（着色器优化）"
)

@Composable
internal fun VulkanDetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content()
            }
        }
    }
}

/** 驱动专区顶部紧凑设备信息卡 */
@Composable
internal fun DriverDeviceSummaryCard(deviceInfo: DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mapVendorDisplayName(deviceInfo.gpuRenderer.ifBlank { appString(R.string.unknown_gpu) }),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DriverInfoChip(label = appString(R.string.chip_arch), value = deviceInfo.adrenoSeries.ifBlank { "—" })
                    DriverInfoChip(label = appString(R.string.chip_ram), value = deviceInfo.ram)
                    DriverInfoChip(label = appString(R.string.chip_android), value = deviceInfo.androidVersion.takeWhile { it != ' ' })
                }
            }
        }
    }
}

/** 驱动更新动态条目：源名 + 家族 + 最新 tag + 时间 + 摘要 + zip 文件名 */
internal data class DriverNewsItem(
    val sourceName: String,
    val family: String,
    val tag: String,
    val date: String,
    val body: String,
    val zipName: String,
    val apiUrl: String,
    /** 从发布说明判出的变更类型（新增支持 / 修复 / 性能 / 破坏性） */
    val kinds: List<String> = emptyList()
)

/**
 * 从发布说明判变更类型。驱动日志基本是英文，按关键词归类即可，
 * 目的是让用户扫一眼知道「这次值不值得更新」，不是做语义理解。
 */
internal fun driverChangeKinds(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val b = body.lowercase()
    val out = mutableListOf<String>()
    if (listOf("support", "add ", "added", "new device", "enable", "introduce").any { b.contains(it) }) {
        out += "新增支持"
    }
    if (listOf("fix", "fixed", "bug", "crash", "hang", "regression").any { b.contains(it) }) {
        out += "修复"
    }
    if (listOf("performance", "faster", "speed", "optimi", "latency", "fps").any { b.contains(it) }) {
        out += "性能"
    }
    if (listOf("breaking", "drop", "removed", "deprecat").any { b.contains(it) }) {
        out += "破坏性"
    }
    return out.take(3)
}

/** 变更类型徽章配色：绿=新增 / 蓝=修复 / 橙=性能 / 红=破坏性 */
private fun driverKindColor(kind: String): Color = when (kind) {
    "新增支持" -> Color(0xFF2E7D32)
    "修复" -> Color(0xFF1565C0)
    "性能" -> Color(0xFFEF6C00)
    else -> Color(0xFFC62828)
}

/** 驱动更新动态卡：全源最新 release 汇总（折叠，默认展示最近 6 条） */
@Composable
internal fun DriverNewsCard(
    items: List<DriverNewsItem>,
    loading: Boolean
) {
    // 原来是折叠卡（默认只露 6 条），实际没人会去展开——改成平铺区块：
    // 全量按时间倒序、按家族筛选、每条带变更类型徽章
    var familyFilter by remember { mutableStateOf("all") }
    var detailItem by remember { mutableStateOf<DriverNewsItem?>(null) }
    // 家族顺序固定：Turnip / 高通 / PanVK / Xclipse / Wrapper。
    // 按「首次出现」排会让高通（只有 1 条、且最旧）掉到屏幕外，用户以为没有这类。
    val families = remember(items) {
        listOf("turnip", "qualcomm", "panvk", "xclipse", "wrapper")
            .filter { f -> items.any { it.family == f } }
    }
    val shown = if (familyFilter == "all") items else items.filter { it.family == familyFilter }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.driver_news_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    Text(
                        "${shown.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            } else if (items.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    appString(R.string.driver_news_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 家族筛选：驱动更新混杂 Turnip / 高通 / PanVK / Wrapper，
                // 用户通常只关心自己 GPU 那一类
                if (families.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("all").plus(families).forEach { f ->
                            FusionFilterChip(
                                label = when (f) {
                                    "all" -> "全部"
                                    "panvk" -> appString(R.string.driver_family_panvk)
                                    "qualcomm" -> appString(R.string.driver_family_qualcomm)
                                    "turnip" -> appString(R.string.driver_family_turnip)
                                    "xclipse" -> "Xclipse"
                                    "wrapper" -> "Wrapper"
                                    else -> f
                                },
                                selected = familyFilter == f,
                                onClick = { familyFilter = f }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                shown.forEach { item ->
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { detailItem = item }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ownerAvatar = githubOwnerAvatar(item.apiUrl)
                        if (ownerAvatar != null) {
                            AsyncImage(
                                model = ownerAvatar,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when (item.family) {
                                        "panvk" -> Color(0xFF1E88E5).copy(alpha = 0.15f)
                                        "qualcomm" -> Color(0xFFF4511E).copy(alpha = 0.15f)
                                        "xclipse" -> Color(0xFF7C3AED).copy(alpha = 0.15f)
                                        "wrapper" -> Color(0xFF616161).copy(alpha = 0.15f)
                                        else -> Color(0xFF43A047).copy(alpha = 0.15f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                when (item.family) {
                                    "panvk" -> appString(R.string.driver_family_panvk)
                                    "qualcomm" -> appString(R.string.driver_family_qualcomm)
                                    "xclipse" -> appString(R.string.driver_family_xclipse)
                                    "wrapper" -> appString(R.string.driver_family_wrapper)
                                    else -> appString(R.string.driver_family_turnip)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (item.family) {
                                    "panvk" -> Color(0xFF1E88E5)
                                    "qualcomm" -> Color(0xFFF4511E)
                                    "xclipse" -> Color(0xFF7C3AED)
                                    "wrapper" -> Color(0xFF616161)
                                    else -> Color(0xFF43A047)
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            LongFieldText(
                                "${item.sourceName} · ${item.tag}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2
                            )
                            if (item.kinds.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    item.kinds.forEach { k ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(driverKindColor(k).copy(alpha = 0.12f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                k,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = driverKindColor(k)
                                            )
                                        }
                                    }
                                }
                            }
                            if (item.zipName.isNotBlank()) {
                                LongFieldText(
                                    item.zipName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatRelativeTime(item.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (item != shown.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
    detailItem?.let { item ->
        DriverReleaseDialog(item = item, onDismiss = { detailItem = null })
    }
}

/** 驱动 release 日志弹窗（近全屏）：release 标题 + 日期 + 完整更新日志。
 *  日志英文时点"翻译"自动译成中文，可随时切回原文；"查看原文"跳 GitHub release 页。 */
@Composable
private fun DriverReleaseDialog(item: DriverNewsItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translated by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    val baseText = cleanReleaseNotes(item.body)
    val showText = cleanReleaseNotes(translated ?: baseText)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${item.sourceName} · ${item.tag}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                    }
                }
                Text(
                    formatRelativeTime(item.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showText.isBlank()) {
                        Text(
                            appString(R.string.driver_news_no_log),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(showText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (baseText.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                if (translated == null) {
                                    translating = true
                                    scope.launch {
                                        translated = translateText(baseText)
                                        translating = false
                                    }
                                } else {
                                    translated = null
                                }
                            },
                            enabled = !translating
                        ) {
                            Text(
                                when {
                                    translating -> appString(R.string.translating)
                                    translated != null -> appString(R.string.show_original)
                                    else -> appString(R.string.translate)
                                }
                            )
                        }
                    }
                    OutlinedButton(onClick = { openNewsLink(context, item.apiUrl) }) {
                        Text(appString(R.string.view_original))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(appString(R.string.close)) }
                }
            }
        }
    }
}

private data class DriverCompareCandidate(
    val sourceId: String,
    val name: String,
    val type: String,
    val description: String,
    val experimental: Boolean,
    val supportedSeries: String,
    val targetRuntime: String,
    val stars: String
)

/** 驱动对比弹窗：候选双选 → 类型/版本/时间/支持 GPU/平台矩阵对比 */
@Composable
private fun DriverCompareDialog(
    turnipSources: List<TurnipSource>,
    panvkSources: List<TurnipSource>,
    qualcommSources: List<QualcommSource>,
    adrenoSeries: String,
    onDismiss: () -> Unit
) {
    val candidates = remember(turnipSources, panvkSources, qualcommSources) {
        buildList {
            turnipSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "Turnip",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.supportedSeries.joinToString("、").ifBlank { "Adreno 全系" },
                        targetRuntime = s.targetEmulator.ifBlank { "通用" }, stars = ""
                    )
                )
            }
            panvkSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "PanVK",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.supportedSeries.joinToString("、").ifBlank { "Mali 全系" },
                        targetRuntime = s.targetEmulator.ifBlank { "通用 (Mali)" }, stars = ""
                    )
                )
            }
            qualcommSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "Qualcomm",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.filters.joinToString("、").ifBlank { "高通全系" },
                        targetRuntime = "系统级 / root", stars = if (s.stars > 0) "$s.stars ⭐" else ""
                    )
                )
            }
        }.sortedBy { it.name }
    }

    // 各源最新 release 的 tag + 发布时间（fetchXxxReleases 有缓存，打开时拉一次）
    val latestBySource = remember { mutableStateMapOf<String, Pair<String, String>>() }
    LaunchedEffect(candidates) {
        candidates.forEach { c ->
            val rel = when (c.type) {
                "Qualcomm" -> qualcommSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchQualcommReleases(it) }.getOrNull() }
                "PanVK" -> panvkSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchTurnipReleases(it, "default") }.getOrNull() }
                else -> turnipSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchTurnipReleases(it, adrenoSeries) }.getOrNull() }
            }
            val first = rel?.firstOrNull()
            latestBySource[c.sourceId] = if (first != null) {
                first.tagName to formatRelativeTime(first.publishedAt)
            } else {
                "" to ""
            }
        }
    }

    val selectedIds = remember { mutableStateListOf<String>() }
    val selected = selectedIds.mapNotNull { id -> candidates.firstOrNull { it.sourceId == id } }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 660.dp)
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(appString(R.string.driver_compare_title), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    appString(R.string.driver_compare_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                if (selected.size == 2) {
                    // ── 对比矩阵 ──
                    val a = selected[0]
                    val b = selected[1]
                    val latestA = latestBySource[a.sourceId] ?: ("" to "")
                    val latestB = latestBySource[b.sourceId] ?: ("" to "")
                    CompareRow(appString(R.string.driver_cmp_name), a.name, b.name)
                    CompareRow(appString(R.string.driver_cmp_type), a.type, b.type)
                    CompareRow(appString(R.string.driver_cmp_latest), latestA.first.ifBlank { "—" }, latestB.first.ifBlank { "—" })
                    CompareRow(appString(R.string.driver_cmp_updated), latestA.second.ifBlank { "—" }, latestB.second.ifBlank { "—" })
                    CompareRow(appString(R.string.driver_cmp_gpu), a.supportedSeries, b.supportedSeries)
                    CompareRow(appString(R.string.driver_cmp_platform), a.targetRuntime, b.targetRuntime)
                    CompareRow(
                        appString(R.string.driver_cmp_flag),
                        if (a.experimental) appString(R.string.experimental_tag) else appString(R.string.recommended_tag),
                        if (b.experimental) appString(R.string.experimental_tag) else appString(R.string.recommended_tag)
                    )
                    CompareRow(appString(R.string.driver_cmp_desc), a.description, b.description)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selectedIds.clear() }) {
                            Text(appString(R.string.driver_cmp_reselect))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(appString(R.string.close))
                        }
                    }
                } else {
                    // ── 候选选择（最多两个）──
                    candidates.forEach { c ->
                        val latest = latestBySource[c.sourceId] ?: ("" to "")
                        val checked = c.sourceId in selectedIds
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            onClick = {
                                when {
                                    checked -> selectedIds.remove(c.sourceId)
                                    selectedIds.size < 2 -> selectedIds.add(c.sourceId)
                                }
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        when {
                                            !on -> selectedIds.remove(c.sourceId)
                                            selectedIds.size < 2 -> selectedIds.add(c.sourceId)
                                        }
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                c.type,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        buildString {
                                            if (latest.first.isNotBlank()) append("${latest.first} · ")
                                            if (latest.second.isNotBlank()) append(latest.second)
                                            if (c.stars.isNotBlank()) append(" · ${c.stars}")
                                        }.ifBlank { c.targetRuntime },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(appString(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareRow(label: String, a: String, b: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    a,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Card(
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    b,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun DriverInfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}
