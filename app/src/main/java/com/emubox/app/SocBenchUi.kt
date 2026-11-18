package com.emubox.app

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.*


/** 主表分数（纯数字）→ 万位展示；非数字原样返回 */
private fun socScoreText(raw: String): String {
    val v = raw.toLongOrNull() ?: return raw
    return if (v >= 10_000) String.format("%.1f万", v / 10_000.0) else raw
}

/** 制程写法转中文：TSMC N3E → 台积电 3nm，TSMC N12 → 台积电 12nm，Samsung 4LPP+ → 三星 4nm。 */
internal fun formatProcess(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw.replace("TSMC", "台积电").replace("Samsung", "三星")
        .replace("SMIC", "中芯国际").replace("Intel", "英特尔")
    // 节点号可能是两位数（N12 / N10），只吃一位会把「N12」拆成「1nm」+ 残留「2」
    Regex("(?<![A-Za-z])N(\\d{1,2})([A-Z]*)").find(s)
        ?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    Regex("(\\d+)LPP\\+?").find(s)?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    Regex("(\\d+)LP\\+?").find(s)?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    return s.trim()
}

private fun gpuArchOf(gpuKey: String): String = when {
    gpuKey.startsWith("adreno") -> "adreno"
    gpuKey.startsWith("mali") -> "mali"
    gpuKey.startsWith("xclipse") -> "xclipse"
    else -> ""
}

/** 芯片知识卡：按部件号识别当前 SoC，展示工艺/CPU/GPU/参考跑分/常见机型 */
@Composable
internal fun SocKnowledgeCard(
    context: Context,
    gpuModel: String = "",
    socHint: String = "",
    gpuIsAdreno: Boolean = false
) {
    // 主表按部件号识别（SM8750-AB / MT6989 / sun）。拿不到时整段退回旧知识库，
    // 旧链路只认营销名，遇到厂商级代号（qcom）会显示未收录，但不会误报。
    val resolved = remember(gpuModel, socHint, gpuIsAdreno) {
        runCatching {
            SocResolver.resolveCurrent(context, gpuModel, socHint, gpuIsAdreno)
        }.getOrNull()
    }
    val socName = resolved?.displayName
        ?: SocNameMapper.getCurrentSocName(context, gpuModel, gpuIsAdreno)
    val knowledge = remember(resolved, socName, gpuModel) {
        if (resolved != null) SocKnowledge(
            name = resolved.displayName,
            process = formatProcess(resolved.process),
            cpu = resolved.cpu,
            gpu = resolved.gpu,
            gpuArch = gpuArchOf(resolved.gpuKey),
            score = socScoreText(resolved.antutu),
            devices = resolved.devices
        ) else SocKnowledgeStore.find(socName, gpuModel, context)
    }
    // 实测规格（安兔兔 clusters）：主表有核心构成时直接用，省一次 JSON 查找
    val spec = remember(resolved, socName) {
        if (resolved != null && resolved.cores > 0) {
            SocSpec(
                cpuSummary = resolved.cpu,
                totalCores = resolved.cores,
                maxFreqGhZ = resolved.maxGhz,
                cpuScore = resolved.antutuCpu,
                gpuScore = resolved.antutuGpu,
                totalScore = resolved.antutu
            )
        } else SocSpecStore.find(socName, context)
    }
    // 跑分引擎命中时隐藏知识库文案分数，避免与真实跑分冲突
    val benchInfo = remember(socName, gpuModel) { BenchEngine.resolve(socName, gpuModel, context) }
    val hasBenchScore = benchInfo?.antutuTotal != null || benchInfo?.ludashiScore != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RegionHeader(
            title = appString(R.string.soc_knowledge_title),
            icon = Icons.Default.Memory,
        )
        if (knowledge == null) {
            // 认不出型号也要认到厂：展锐上报 ums9230、华为上报 kirin9020，
            // 直接显示裸代号对用户没有信息量，显示「紫光展锐 · 型号未收录（ums9230）」才有。
            // 取「真正能定位到厂商」的那个字段：有些 ROM 把 ro.soc.model 写成 unknown，
            // 此时厂商是从 ro.board.platform 认出来的，代号就该显示 platform
            val rawCode = remember(gpuModel, socHint) {
                val id = SocNameMapper.getRawIdentity()
                listOf(id.socModel, id.platform, id.hardware)
                    .firstOrNull { it.isNotBlank() && !it.equals("unknown", true) }
                    .orEmpty()
            }
            val vendor = remember(rawCode) {
                runCatching {
                    SocResolver.vendorOf(context, SocNameMapper.getRawIdentity())
                }.getOrDefault("")
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val brand = detectSocBrand(vendor.ifBlank { socName })
                        if (brand.name != "SoC") {
                            SocBrandBadge(brand = brand, text = brand.name)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            vendor.ifBlank { socName.ifBlank { "SOC: unknown" } },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (vendor.isNotBlank() && rawCode.isNotBlank()) {
                            appString(R.string.soc_vendor_only, rawCode)
                        } else {
                            appString(R.string.soc_knowledge_not_found)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 厂商专属图标徽章（品牌色+图形，与设备身份卡一致）
                        val brand = detectSocBrand(knowledge.name)
                        if (brand.name != "SoC") {
                            SocBrandBadge(brand = brand, text = brand.name)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            knowledge.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!hasBenchScore && knowledge.score.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    knowledge.score,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!hasBenchScore) {
                        InfoRow(
                            label = appString(R.string.soc_score_label),
                            value = knowledge.score.ifBlank { appString(R.string.soc_score_unknown) }
                        )
                    }
                    // 主表有的芯片工艺/常见机型为空（极客湾未填），空值行会显示成一条横线，直接不画
                    if (knowledge.process.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_process), value = knowledge.process)
                    }
                    val cpuText = spec?.cpuSummary?.takeIf { it.isNotBlank() } ?: knowledge.cpu
                    if (cpuText.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_cpu), value = cpuText)
                    }
                    // 缓存（L2/SLC）：极客湾只有旗舰标，有值才画
                    if (resolved != null && resolved.caches.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_caches), value = resolved.caches)
                    }
                    if (spec != null && spec.totalCores > 0) {
                        val freq = if (spec.maxFreqGhZ > 0) " · 最高 ${formatFreq(spec.maxFreqGhZ)}" else ""
                        InfoRow(label = appString(R.string.soc_cpu_cores), value = "${spec.totalCores} 核$freq")
                    }
                    // GPU 规格合成一行：Adreno 840 · 1536ALUs · 1200MHz
                    val gpuSpec = if (resolved != null) {
                        listOfNotNull(
                            knowledge.gpu.takeIf { it.isNotBlank() },
                            resolved.gpuCores.takeIf { it.isNotBlank() },
                            resolved.gpuClock.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    } else knowledge.gpu
                    if (gpuSpec.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_gpu_spec), value = gpuSpec)
                    }
                    if (resolved != null && resolved.memory.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_memory), value = resolved.memory)
                    }
                    // 集成功能：基带 / Wi-Fi / 蓝牙 / NPU（极客湾 features 串）
                    if (resolved != null && resolved.features.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_features), value = resolved.features)
                    }
                    if (resolved != null && resolved.gb6Single > 0) {
                        InfoRow(
                            label = appString(R.string.soc_gb6),
                            value = "${resolved.gb6Single} / ${resolved.gb6Multi}"
                        )
                    }
                    // 型号名称：设备上报的那个部件号，用户最需要核对的字段
                    if (resolved != null && resolved.codes.isNotEmpty()) {
                        InfoRow(
                            label = appString(R.string.soc_model_name),
                            value = resolved.codes.first()
                        )
                    }
                    if (knowledge.devices.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_devices), value = knowledge.devices)
                    }
                    // 时效性与口径：哪年发布、什么档位、是不是嵌入式件。
                    // 主表里 QCS/QCM 是车机与 IoT 模组，跑分比同期手机芯片高，
                    // 不标出来会被当成手机芯片看。
                    val metaLine = remember(resolved) {
                        if (resolved == null) "" else buildList {
                            if (resolved.year.isNotBlank()) add("${resolved.year} 年发布")
                            when (resolved.tier) {
                                "flagship" -> add("旗舰")
                                "sub-flagship" -> add("次旗舰")
                                "mid" -> add("中端")
                                "low" -> add("入门")
                            }
                            val embedded = resolved.codes.any { c ->
                                c.startsWith("QCS", true) || c.startsWith("QCM", true)
                            } || resolved.id.startsWith("qcs", true) || resolved.id.startsWith("qcm", true)
                            if (embedded) add("嵌入式 / 车机版")
                        }.joinToString(" · ")
                    }
                    if (metaLine.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        appString(R.string.soc_source_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/** 常见机型太长（「小米 13、iQOO Neo9、一加 11、红魔 8」），卡片里只留前三款 */
private fun compactDevices(devices: String, max: Int = 3): String {
    val parts = devices.split('、', '，', ',', '/').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return devices
    return if (parts.size <= max) parts.joinToString("、") else parts.take(max).joinToString("、") + " 等"
}

/** SoC 跑分引擎：等效 PC / 模拟器体验预估 / 升级建议 / 世代演进 / 同芯机型。
 *  数据来自安兔兔 SoC 天梯 + 鲁大师五榜（assets/bench，CI 每日刷新）。
 *  匹配失败或数据缺失一律展示空态，不编造分数。 */
@Composable
internal fun BenchEngineCard(context: Context, gpuModel: String = "") {
    val socName = remember { SocNameMapper.getCurrentSocName(context) }
    val info = remember(socName, gpuModel) { BenchEngine.resolve(socName, gpuModel, context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RegionHeader(
            title = appString(R.string.bench_engine_title),
            icon = Icons.Default.TrendingUp,
        )
        if (info == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        socName.ifBlank { "SOC: unknown" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        // 芯片身份行：厂商徽章 + 芯片名 + 安兔兔总分（无则隐藏总分）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val brand = detectSocBrand(info.name)
                    if (brand.name != "SoC") {
                        SocBrandBadge(brand = brand, text = brand.name)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (info.antutuTotal != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "安兔兔 ${info.antutuTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
                // 本机机型跑分（安兔兔 V10 手机榜按具体机型计分，含配置差异）
                if (info.deviceBench != null) {
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    val fmt = { v: Long -> String.format("%,d", v) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本机机型跑分",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${info.deviceBench.name}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            fmt(info.deviceBench.totalScore!!),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val db = info.deviceBench
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        db.cpuScore?.let { Text("CPU ${fmt(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        db.gpuScore?.let { Text("GPU ${fmt(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("配置 ${db.soc}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "安兔兔 V10 手机榜按具体机型计分；处理器分为 SoC 天梯口径，两者口径不同",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // GPU 算力对照：只比物理量（FP32 TFLOPS / 带宽），不比跑分。
        // 旧实现用「鲁大师同分制最近邻」把手机 GPU 分和桌面 GPU 分对齐，
        // 两张榜口径不同却数值相近，8 Gen 2 被算成 RTX 2060 SUPER（实际差 8~10 倍）。
        val gpuPhys = info.gpuPhys
        if (gpuPhys != null) {
            val g = gpuPhys
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gpu_phys_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_local),
                        value = "${g.gpuName} · FP32 ${g.tf32} TFLOPS · ${g.bwGbs} GB/s"
                    )
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_desk),
                        value = "${g.deskName}（${g.deskTf32} TFLOPS / ${g.deskBwGbs} GB/s）"
                    )
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_rel_label),
                        value = appString(R.string.bench_gpu_phys_rel, g.tfPct, g.bwPct)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_gpu_phys_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        } else if (info.antutuTotal != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gpu_phys_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_gpu_phys_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 模拟器体验：改用 EmuReady 真实社区报告，不再用「安兔兔总分阈值」自估。
        // 旧阈值（Winlator 流畅 280 万 / 不建议 120 万）把 8 Gen 2 判成「不建议」，
        // 而 EmuReady 上 8 Gen 2 跑 Winlator 有 800+ 条报告、均分 73 —— 自估阈值不可信。
        val socCompat = remember(socName) { EmuReadySocStore.find(context, socName) }
        val emuSystems = socCompat?.systems.orEmpty()
        if (emuSystems.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_emu_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        appString(R.string.bench_emu_source, emuSystems.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    emuSystems.take(6).forEachIndexed { idx, sys ->
                        if (idx > 0) HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        CompatRow(
                            name = emuSystemZh(sys.name, sys.key),
                            emulator = sys.bestEmulator,
                            score = sys.score,
                            confidence = sys.confidence,
                            source = sys.dataSource,
                            listings = sys.listings
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        appString(R.string.bench_emu_calib),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        } else if (info.antutuTotal != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_emu_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_emu_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 升级建议
        info.upgrade?.let { u ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_upgrade_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = if (u.altName != null) "同厂商首选" else appString(R.string.bench_upgrade_next),
                        value = "${u.nextName}（+${u.gainPct}%）"
                    )
                    // 常见机型来自 soc_master 的 devices 字段：告诉用户「换到这颗芯片，
                    // 对应的机器是哪些」，比只给一个芯片名有用
                    if (u.nextDevices.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        InfoRow(label = "常见机型", value = compactDevices(u.nextDevices))
                    }
                    if (u.altName != null) {
                        Spacer(Modifier.height(4.dp))
                        InfoRow(
                            label = "次选（跨厂商）",
                            value = "${u.altName}（+${u.altGainPct}%）"
                        )
                        if (u.altDevices.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            InfoRow(label = "常见机型", value = compactDevices(u.altDevices))
                        }
                    }
                }
            }
        }

        // 世代演进（同家族按发布年柱状）
        if (info.generation.size >= 3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gen_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    BenchGenerationBars(info.generation)
                }
            }
        }

        // 同芯机型跑分（榜单未收录机型折叠为说明，不刷屏）
        info.sameChip?.let { list ->
            val scored = list.filter { it.score != null }
            if (scored.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            appString(R.string.bench_samechip_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        scored.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    d.score?.let { "%,d".format(it) } ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (scored.size < list.size) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                appString(R.string.bench_samechip_partial),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}


/** 世代演进列表（同家族逐年，按发布年排序，分数条归一化；信息完整不截断） */
@Composable
private fun BenchGenerationBars(data: List<Triple<String, String, Long>>) {
    val max = data.maxOf { it.third }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.forEachIndexed { idx, (year, name, score) ->
            val pct = ((score.toFloat() / max.toFloat()) * 100f).coerceAtLeast(2f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    year,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format("%,d", score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = 0f)
                    .then(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (idx == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                    )
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "同家族 ${data.size} 代按发布年排序（分数条按总分归一化）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
