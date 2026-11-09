package com.emubox.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.emubox.drivercheck.DriverCheck
import com.emubox.drivercheck.PatternCheck
import com.emubox.drivercheck.RenderReport
import com.emubox.drivercheck.ui.DriverCheckSheet
import com.emubox.drivercheck.ui.DriverCheckState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个可被实测的目标驱动。
 *
 * @param id 下载记录 id，用于 DriverExtractor 的缓存目录
 * @param label 展示名（通常取包文件名）
 * @param packagePath 驱动包本地路径或 content:// URI，需要时才会解压取 .so
 */
data class DriverTarget(
    val id: String,
    val label: String,
    val packagePath: String,
)

/**
 * 驱动渲染实测入口（自研内核，闭源 AAR）。
 *
 * 与旧的「渲染测试」区别：旧链路只回一行 FPS 文字，驱动画错了也看不出；
 * 这里把回读的像素直接铺成图，配 N 项图案断言，问题驱动会显示成一张错图。
 *
 * 两种取驱动方式：
 * - 不选驱动（默认「系统驱动」）走系统 libvulkan.so.1；
 * - 选中某个已下载驱动则用 dlopen 直接加载它的 .so，绕开系统 loader。
 *   后者在 App 进程内可能被 namespace/SELinux 拒绝，失败时报告里带 dlerror 原文，
 *   那本身就是有用的信息（说明该驱动不能裸加载，必须走 AdrenoTools 注入）。
 */
@Composable
fun DriverRenderCheckCard(
    modifier: Modifier = Modifier,
    drivers: List<DriverTarget> = emptyList(),
    // 仅用于截图/预览测试注入已完成状态，正式调用保持默认 Idle
    initialState: DriverCheckState = DriverCheckState.Idle,
    // 跑分结果回传：设备指南与结论文案要用它显示「本机实测总分」
    onBenchResult: (BenchmarkResult) -> Unit = {},
) {
    var state by remember(initialState) { mutableStateOf(initialState) }
    var showSheet by remember { mutableStateOf(false) }
    // null = 系统驱动
    var selected by remember(drivers) { mutableStateOf<DriverTarget?>(null) }
    // 带宽/算力跑分：与出图共用同一个驱动库，结果跟着这张卡走
    var bench by remember { mutableStateOf<BenchmarkResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 文案在 composable 上下文取好，onClick 里不能调 @Composable
    val exportLabel = appString(R.string.driver_check_export)
    val exportSubject = appString(R.string.driver_check_export_subject)
    val extractFailed = appString(R.string.driver_check_extract_failed)

    fun start() {
        val target = selected
        state = DriverCheckState.Running
        bench = null
        scope.launch {
            var soPath: String? = null
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    // 选中驱动时才解压取 .so，避免每次进页面都做重活
                    soPath = target?.let { t ->
                        val cached = DriverExtractor.getSoFile(context, t.id)?.takeIf { it.exists() }
                        val so = cached ?: runCatching {
                            val uri = if (t.packagePath.startsWith("content://")) Uri.parse(t.packagePath)
                            else Uri.fromFile(File(t.packagePath))
                            DriverExtractor.extract(context, uri, t.id)
                        }.getOrNull()
                        so?.absolutePath ?: error(extractFailed)
                    }
                    DriverCheck.run(driverPath = soPath)
                }
            }
            // 出图之后紧接着跑带宽/算力：同一份驱动、同一个进程，
            // 这样「能不能画对」和「画得多快」是一条结果，不再拆成两个入口。
            if (res.isSuccess) {
                bench = withContext(Dispatchers.IO) {
                    runCatching {
                        BenchmarkResult.fromJson(NativeVulkanBridge.benchmarkVulkan(soPath))
                    }.getOrNull()
                }
                bench?.let { onBenchResult(it) }
            }
            state = res.fold(
                onSuccess = { (bmp, report) ->
                    if (bmp == null && !report.ok) {
                        // 只存原始错误串，展示时再走本地化（协程内不能调 @Composable）
                        DriverCheckState.Failed(report.error)
                    } else {
                        DriverCheckState.Done(
                            image = bmp,
                            report = report,
                            checks = bmp?.let { DriverCheck.checkPattern(it) } ?: emptyList(),
                        )
                    }
                },
                onFailure = {
                    DriverCheckState.Failed(it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appString(R.string.driver_check_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val doneForExport = state as? DriverCheckState.Done
                if (doneForExport != null) {
                    TextButton(onClick = {
                        val log = buildLog(doneForExport.report, doneForExport.checks)
                        // 直接落盘到 Download/EmuBox/logs（或设置里选的目录），
                        // 不再走 ACTION_SEND —— 部分 ROM 会把纯文本分享当成浏览器搜索
                        val result = LogExporter.save(context, "emubox-drivercheck", log)
                        Toast.makeText(
                            context,
                            if (result.ok) "日志已保存：${result.displayPath}"
                            else "日志保存失败：${result.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text(exportLabel)
                    }
                }
                TextButton(
                    onClick = {
                        showSheet = true
                        if (state !is DriverCheckState.Running) start()
                    },
                    enabled = state !is DriverCheckState.Running,
                ) {
                    Text(
                        appString(
                            if (state is DriverCheckState.Idle) R.string.driver_check_run
                            else R.string.driver_check_rerun
                        )
                    )
                }
            }
            // 驱动选择：系统驱动 / 已下载驱动。只有一个选项（系统）时整行隐藏，
            // 避免卡片在没有任何驱动的设备上多出一排无意义控件。
            if (drivers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selected == null,
                        onClick = {
                            selected = null
                            if (state !is DriverCheckState.Running) state = DriverCheckState.Idle
                        },
                        enabled = state !is DriverCheckState.Running,
                        label = { Text(appString(R.string.driver_check_use_system), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                    drivers.forEach { d ->
                        FilterChip(
                            selected = selected?.id == d.id,
                            onClick = {
                                selected = d
                                if (state !is DriverCheckState.Running) state = DriverCheckState.Idle
                            },
                            enabled = state !is DriverCheckState.Running,
                            label = {
                                Text(
                                    d.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            Text(
                appString(R.string.driver_check_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val target = selected
            val statusText = when (val s = state) {
                is DriverCheckState.Done -> summary(s.report, s.checks.count { it.ok }, s.checks.size)
                is DriverCheckState.Failed -> appString(
                    R.string.driver_check_failed,
                    s.message.ifBlank { appString(R.string.driver_check_no_image) },
                )
                is DriverCheckState.Running -> appString(R.string.driver_check_running_label)
                else -> if (target == null) appString(R.string.driver_check_system_note)
                else appString(R.string.driver_check_target_note, target.label)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is DriverCheckState.Running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state) {
                        is DriverCheckState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 最近一次的图像直接贴在卡片里，不用打开抽屉也能看到画面
            val done = state as? DriverCheckState.Done
            val image: Bitmap? = done?.image
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = appString(R.string.driver_check_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
            }

            // 跑分：填充/拷贝带宽 + 计算吞吐 + 综合分。数字都来自 native 的
            // timestamp query，不做归一化，不同驱动之间可直接对比。
            bench?.let { b ->
                Spacer(Modifier.height(2.dp))
                if (b.success) {
                    BenchRow("填充带宽", "%.1f GB/s".format(b.fillBandwidthGBs))
                    BenchRow("拷贝带宽", "%.1f GB/s".format(b.copyBandwidthGBs))
                    if (b.computeGFLOPS > 0) BenchRow("计算吞吐", "%.1f GFLOPS".format(b.computeGFLOPS))
                    BenchRow("综合分", "${b.totalScore}")
                } else {
                    Text(
                        "跑分未完成：${b.errorMessage ?: b.errorCode ?: "未知原因"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showSheet) {
        DriverCheckSheet(
            state = state,
            onRun = { start() },
            onDismiss = { showSheet = false },
        )
    }
}

/** 左标签右数值的一行，跑分结果用 */
@Composable
private fun BenchRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 驱动反馈日志（schema emubox.drivercheck/1）。
 *
 * 字段刻意对齐各家驱动 issue 模板常见项：设备型号 + SoC + Android 版本 + 驱动版本 +
 * Vulkan API 版本 + 渲染结论 + 逐步骤成败 + 逐项图案断言。纯 JSON，方便对方直接解析。
 */
private fun buildLog(report: RenderReport, checks: List<PatternCheck>): String {
    val root = JSONObject()
    root.put("schema", "emubox.drivercheck/1")
    root.put("generatedAt", System.currentTimeMillis())

    val device = JSONObject()
        .put("model", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        device.put("soc", Build.SOC_MODEL)
        device.put("socManufacturer", Build.SOC_MANUFACTURER)
    }
    root.put("device", device)

    root.put("gpu", JSONObject()
        .put("name", report.deviceName)
        .put("driverVersion", report.driverVersion)
        .put("apiVersion", report.apiVersion)
        .put("vendorId", String.format("0x%04X", report.vendorId))
        .put("deviceId", String.format("0x%04X", report.deviceId)))

    root.put("render", JSONObject()
        .put("ok", report.ok)
        .put("error", report.error)
        .put("size", "${report.width}x${report.height}")
        .put("hash", report.hash)
        .put("hashRepeat", report.hashRepeat)
        .put("consistent", report.consistent)
        .put("msPerFrame", report.renderMs)
        .put("checksPassed", checks.count { it.ok })
        .put("checksTotal", checks.size))

    root.put("steps", JSONArray().apply {
        report.steps.forEach {
            put(JSONObject().put("name", it.name).put("ok", it.ok).put("detail", it.detail))
        }
    })
    root.put("checks", JSONArray().apply {
        checks.forEach {
            put(
                JSONObject()
                    .put("name", it.name)
                    .put("ok", it.ok)
                    .put("expected", it.expected)
                    .put("actual", it.actual)
            )
        }
    })
    return root.toString(2)
}

@Composable
private fun summary(report: RenderReport, passed: Int, total: Int): String {
    if (!report.ok) {
        return report.error.ifBlank { appString(R.string.driver_check_no_image) }
    }
    return appString(R.string.driver_check_summary, passed, total, report.renderMs)
}
