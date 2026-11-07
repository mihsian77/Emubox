package com.emubox.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
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
 * 驱动渲染实测入口（自研内核，闭源 AAR）。
 *
 * 与旧的「渲染测试」区别：旧链路只回一行 FPS 文字，驱动画错了也看不出；
 * 这里把回读的像素直接铺成图，配 N 项图案断言，问题驱动会显示成一张错图。
 *
 * 走系统 Vulkan 驱动，不依赖窗口 Surface，所以旋转/前后台不会引入假失败。
 */
@Composable
fun DriverRenderCheckCard(
    modifier: Modifier = Modifier,
    // 仅用于截图/预览测试注入已完成状态，正式调用保持默认 Idle
    initialState: DriverCheckState = DriverCheckState.Idle,
) {
    var state by remember(initialState) { mutableStateOf(initialState) }
    var showSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 文案在 composable 上下文取好，onClick 里不能调 @Composable
    val exportLabel = appString(R.string.driver_check_export)
    val exportSubject = appString(R.string.driver_check_export_subject)

    fun start() {
        state = DriverCheckState.Running
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { DriverCheck.run() }
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
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                            putExtra(Intent.EXTRA_TEXT, log)
                        }
                        context.startActivity(
                            Intent.createChooser(send, exportLabel)
                        )
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
            Text(
                appString(R.string.driver_check_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val statusText = when (val s = state) {
                is DriverCheckState.Done -> summary(s.report, s.checks.count { it.ok }, s.checks.size)
                is DriverCheckState.Failed -> appString(
                    R.string.driver_check_failed,
                    s.message.ifBlank { appString(R.string.driver_check_no_image) },
                )
                is DriverCheckState.Running -> appString(R.string.driver_check_running_label)
                else -> appString(R.string.driver_check_system_note)
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
