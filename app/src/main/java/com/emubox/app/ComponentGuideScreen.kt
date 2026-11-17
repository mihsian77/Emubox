package com.emubox.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.theme.Fusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*


// ---------- Component guide ----------
private data class GuideTopic(
    val id: String,
    @StringRes val categoryRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
    @StringRes val badgeRes: Int,
    @StringRes val whatItIsRes: Int,
    @StringRes val tryFirstRes: Int,
    @StringRes val bestForRes: Int,
    @StringRes val switchWhenRes: Int,
    val tipRes: List<Int>,
    val stepsRes: List<Int> = emptyList()
)

private val componentGuideTopics = listOf(
    GuideTopic(
        id = "turnip",
        categoryRes = R.string.category_gpu_driver,
        titleRes = R.string.guide_turnip_title,
        subtitleRes = R.string.guide_turnip_subtitle,
        icon = Icons.Default.Eco,
        badgeRes = R.string.badge_recommended,
        whatItIsRes = R.string.guide_turnip_whatitis,
        tryFirstRes = R.string.guide_turnip_tryfirst,
        bestForRes = R.string.guide_turnip_bestfor,
        switchWhenRes = R.string.guide_turnip_switchwhen,
        tipRes = listOf(
            R.string.guide_turnip_tip1,
            R.string.guide_turnip_tip2,
            R.string.guide_turnip_tip3
        ),
        stepsRes = listOf(
            R.string.guide_step_turnip_1,
            R.string.guide_step_turnip_2,
            R.string.guide_step_turnip_3,
            R.string.guide_step_turnip_4
        )
    ),
    GuideTopic(
        id = "qualcomm",
        categoryRes = R.string.category_gpu_driver,
        titleRes = R.string.guide_qualcomm_title,
        subtitleRes = R.string.guide_qualcomm_subtitle,
        icon = Icons.Default.Memory,
        badgeRes = R.string.badge_alternative,
        whatItIsRes = R.string.guide_qualcomm_whatitis,
        tryFirstRes = R.string.guide_qualcomm_tryfirst,
        bestForRes = R.string.guide_qualcomm_bestfor,
        switchWhenRes = R.string.guide_qualcomm_switchwhen,
        tipRes = listOf(
            R.string.guide_qualcomm_tip1,
            R.string.guide_qualcomm_tip2,
            R.string.guide_qualcomm_tip3
        ),
        stepsRes = listOf(
            R.string.guide_step_qualcomm_1,
            R.string.guide_step_qualcomm_2,
            R.string.guide_step_qualcomm_3,
            R.string.guide_step_qualcomm_4
        )
    ),
    GuideTopic(
        id = "wine",
        categoryRes = R.string.category_windows,
        titleRes = R.string.guide_wine_title,
        subtitleRes = R.string.guide_wine_subtitle,
        icon = Icons.Default.WineBar,
        badgeRes = R.string.badge_base_runtime,
        whatItIsRes = R.string.guide_wine_whatitis,
        tryFirstRes = R.string.guide_wine_tryfirst,
        bestForRes = R.string.guide_wine_bestfor,
        switchWhenRes = R.string.guide_wine_switchwhen,
        tipRes = listOf(
            R.string.guide_wine_tip1,
            R.string.guide_wine_tip2,
            R.string.guide_wine_tip3
        ),
        stepsRes = listOf(
            R.string.guide_step_wine_1,
            R.string.guide_step_wine_2,
            R.string.guide_step_wine_3
        )
    ),
    GuideTopic(
        id = "proton",
        categoryRes = R.string.category_windows,
        titleRes = R.string.guide_proton_title,
        subtitleRes = R.string.guide_proton_subtitle,
        icon = Icons.Default.Bolt,
        badgeRes = R.string.badge_games,
        whatItIsRes = R.string.guide_proton_whatitis,
        tryFirstRes = R.string.guide_proton_tryfirst,
        bestForRes = R.string.guide_proton_bestfor,
        switchWhenRes = R.string.guide_proton_switchwhen,
        tipRes = listOf(
            R.string.guide_proton_tip1,
            R.string.guide_proton_tip2,
            R.string.guide_proton_tip3
        ),
        stepsRes = listOf(
            R.string.guide_step_proton_1,
            R.string.guide_step_proton_2,
            R.string.guide_step_proton_3
        )
    ),
    GuideTopic(
        id = "box64",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_box64_title,
        subtitleRes = R.string.guide_box64_subtitle,
        icon = Icons.Default.Inventory2,
        badgeRes = R.string.badge_common_default,
        whatItIsRes = R.string.guide_box64_whatitis,
        tryFirstRes = R.string.guide_box64_tryfirst,
        bestForRes = R.string.guide_box64_bestfor,
        switchWhenRes = R.string.guide_box64_switchwhen,
        tipRes = listOf(
            R.string.guide_box64_tip1,
            R.string.guide_box64_tip2,
            R.string.guide_box64_tip3
        )
    ),
    GuideTopic(
        id = "wowbox64",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_wowbox64_title,
        subtitleRes = R.string.guide_wowbox64_subtitle,
        icon = Icons.Default.AutoAwesome,
        badgeRes = R.string.badge_advanced,
        whatItIsRes = R.string.guide_wowbox64_whatitis,
        tryFirstRes = R.string.guide_wowbox64_tryfirst,
        bestForRes = R.string.guide_wowbox64_bestfor,
        switchWhenRes = R.string.guide_wowbox64_switchwhen,
        tipRes = listOf(
            R.string.guide_wowbox64_tip1,
            R.string.guide_wowbox64_tip2,
            R.string.guide_wowbox64_tip3
        )
    ),
    GuideTopic(
        id = "fexcore",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_fexcore_title,
        subtitleRes = R.string.guide_fexcore_subtitle,
        icon = Icons.Default.DeveloperBoard,
        badgeRes = R.string.badge_alternative,
        whatItIsRes = R.string.guide_fexcore_whatitis,
        tryFirstRes = R.string.guide_fexcore_tryfirst,
        bestForRes = R.string.guide_fexcore_bestfor,
        switchWhenRes = R.string.guide_fexcore_switchwhen,
        tipRes = listOf(
            R.string.guide_fexcore_tip1,
            R.string.guide_fexcore_tip2,
            R.string.guide_fexcore_tip3
        )
    ),
    GuideTopic(
        id = "dxvk",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_dxvk_title,
        subtitleRes = R.string.guide_dxvk_subtitle,
        icon = Icons.Default.SportsEsports,
        badgeRes = R.string.badge_dx8_11,
        whatItIsRes = R.string.guide_dxvk_whatitis,
        tryFirstRes = R.string.guide_dxvk_tryfirst,
        bestForRes = R.string.guide_dxvk_bestfor,
        switchWhenRes = R.string.guide_dxvk_switchwhen,
        tipRes = listOf(
            R.string.guide_dxvk_tip1,
            R.string.guide_dxvk_tip2,
            R.string.guide_dxvk_tip3
        )
    ),
    GuideTopic(
        id = "vkd3d",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_vkd3d_title,
        subtitleRes = R.string.guide_vkd3d_subtitle,
        icon = Icons.Default.ViewInAr,
        badgeRes = R.string.badge_dx12,
        whatItIsRes = R.string.guide_vkd3d_whatitis,
        tryFirstRes = R.string.guide_vkd3d_tryfirst,
        bestForRes = R.string.guide_vkd3d_bestfor,
        switchWhenRes = R.string.guide_vkd3d_switchwhen,
        tipRes = listOf(
            R.string.guide_vkd3d_tip1,
            R.string.guide_vkd3d_tip2,
            R.string.guide_vkd3d_tip3
        )
    ),
    GuideTopic(
        id = "d7vk",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_d7vk_title,
        subtitleRes = R.string.guide_d7vk_subtitle,
        icon = Icons.Default.Gamepad,
        badgeRes = R.string.badge_legacy_games,
        whatItIsRes = R.string.guide_d7vk_whatitis,
        tryFirstRes = R.string.guide_d7vk_tryfirst,
        bestForRes = R.string.guide_d7vk_bestfor,
        switchWhenRes = R.string.guide_d7vk_switchwhen,
        tipRes = listOf(
            R.string.guide_d7vk_tip1,
            R.string.guide_d7vk_tip2,
            R.string.guide_d7vk_tip3
        )
    )
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentGuideScreen(
    onBack: () -> Unit,
    adrenoSeries: String?,
    initialTopicId: String? = null,
    driverMode: Boolean = false,
    deviceInfo: DeviceInfo? = null
) {
    BackHandler { onBack() }

    val listState = rememberLazyListState()
    var expandedId by rememberSaveable { mutableStateOf(initialTopicId) }

    // 驱动指南只保留驱动相关主题（Turnip/AdrenoTools/PanVK/厂商驱动），
    // 组件指南只保留 Wine/Proton/DXVK 等运行环境与图形组件主题。
    val driverTopicIds = setOf("turnip", "qualcomm", "adrenotools", "panvk")
    val visibleTopics = componentGuideTopics.filter {
        if (driverMode) it.id in driverTopicIds else it.id !in driverTopicIds
    }
    // 按当前设备 GPU 重排主题：Adreno 用户 Turnip/高通驱动优先，Mali 用户 PanVK 优先
    val isAdrenoGuide = deviceInfo?.gpuVendor == GpuVendor.ADRENO
    val isMaliGuide = deviceInfo?.gpuVendor == GpuVendor.MALI
    val sortedTopics = remember(visibleTopics, isAdrenoGuide, isMaliGuide) {
        if (driverMode) {
            val order = when {
                isAdrenoGuide -> listOf("turnip", "qualcomm", "adrenotools", "panvk")
                isMaliGuide -> listOf("panvk", "adrenotools", "turnip", "qualcomm")
                else -> listOf("turnip", "qualcomm", "adrenotools", "panvk")
            }
            visibleTopics.sortedBy { t ->
                val i = order.indexOf(t.id)
                if (i < 0) Int.MAX_VALUE else i
            }
        } else visibleTopics
    }

    LaunchedEffect(initialTopicId) {
        if (initialTopicId != null) {
            val index = visibleTopics.indexOfFirst { it.id == initialTopicId }
            if (index >= 0) {
                expandedId = initialTopicId
                // 主题卡前面只剩「说明行」一个 item，偏移量跟着信息架构走，别再写死 +3
                listState.animateScrollToItem(index + 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (driverMode) appString(R.string.driver_guide) else appString(R.string.component_guide))
                        Text(
                            if (driverMode) appString(R.string.driver_guide_subtitle) else appString(R.string.what_each_download_does),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = appString(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部只留一句话说明：标题和副标题已在 TopAppBar，再来一张大卡是重复
            item(key = "guide_intro") {
                Text(
                    appString(
                        if (driverMode) R.string.guide_intro_desc_driver
                        else R.string.guide_intro_desc
                    ),
                    fontSize = 12.sp,
                    color = Fusion.OnSurfaceDim,
                )
            }

            sortedTopics.forEach { topic ->
                item(key = "guide_topic_${topic.id}") {
                    GuideTopicCard(
                        topic = topic,
                        expanded = expandedId == topic.id,
                        onClick = {
                            expandedId = if (expandedId == topic.id) null else topic.id
                        }
                    )
                }
            }

            item(key = "guide_quick_pick") {
                QuickPickGuideCard(adrenoSeries, driverMode, deviceInfo)
            }

            // 配置推荐：整合两个 Winlator 社区配置仓库（bannerlator-game-configs +
            // Winlator-Mali-Community-Configs），按 GPU 型号的热度排行给出最常用组件组合
            item(key = "guide_config_recs") {
                WinlatorConfigRecsCard(deviceInfo = deviceInfo)
            }

            item(key = "guide_notice") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            appString(R.string.guide_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        }
    }
}

// ── 配置推荐（Winlator 社区热度聚合）─────────────────────────────
data class ComboRec(val combo: List<Pair<String, String>>, val count: Int, val share: Double)

/** 远程聚合配置源：CI 每日聚合两社区仓库后提交，App 远程拉取优先、内置资产兜底 */
private const val RECIPES_REMOTE_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/app/src/main/assets/winlator-recipes.json"

fun loadWinlatorRecipes(context: Context): Pair<Map<String, List<ComboRec>>, Map<String, List<ComboRec>>> {
    return try {
        // 远程优先：拿到 CI 最新聚合结果；失败回退内置资产（离线可用）
        val remoteText = runCatching { RemoteData.fetchText(RECIPES_REMOTE_URL) }.getOrNull()
        val text = if (!remoteText.isNullOrBlank()) remoteText
            else context.assets.open("winlator-recipes.json").bufferedReader().use { it.readText() }
        val j = org.json.JSONObject(text)
        fun parse(obj: org.json.JSONObject): Map<String, List<ComboRec>> {
            val out = mutableMapOf<String, List<ComboRec>>()
            val names = obj.names() ?: return out
            for (i in 0 until names.length()) {
                val gpu = names.getString(i)
                val top = obj.getJSONObject(gpu).getJSONArray("top")
                val list = mutableListOf<ComboRec>()
                for (k in 0 until top.length()) {
                    val t = top.getJSONObject(k)
                    val comboArr = t.getJSONArray("combo")
                    val combo = mutableListOf<Pair<String, String>>()
                    for (m in 0 until comboArr.length()) {
                        val item = comboArr.getJSONArray(m)
                        combo.add(item.getString(0) to item.getString(1))
                    }
                    list.add(ComboRec(combo, t.getInt("count"), t.optDouble("share", 0.0)))
                }
                out[gpu] = list
            }
            return out
        }
        parse(j.getJSONObject("gpu_combos")) to parse(j.getJSONObject("mali_combos"))
    } catch (e: Exception) {
        emptyMap<String, List<ComboRec>>() to emptyMap<String, List<ComboRec>>()
    }
}

private fun comboText(combo: List<Pair<String, String>>): String =
    combo.joinToString(" · ") { (k, v) -> "$k $v" }

@Composable
private fun WinlatorConfigRecsCard(deviceInfo: DeviceInfo?) {
    val context = LocalContext.current
    // 不能在 composition 里同步取数：原来 remember 直接调 loadWinlatorRecipes，
    // 打开指南页会卡住主线程直到网络超时。改成进页面后异步拉，拿到再显示。
    var recipes by remember {
        mutableStateOf<Pair<Map<String, List<ComboRec>>, Map<String, List<ComboRec>>>?>(null)
    }
    LaunchedEffect(Unit) {
        recipes = withContext(Dispatchers.IO) { loadWinlatorRecipes(context) }
    }
    val (adrenoMap, maliMap) = recipes ?: return
    val gpuModel = deviceInfo?.gpuModel.orEmpty()
    val isMali = deviceInfo?.gpuVendor == GpuVendor.MALI
    // 设备 gpuModel 为纯数字（如 "740"），recipes 键为 "Adreno-740"/"Mali-G57"，需归一化；
    // 精确未命中再做 contains 兜底（应对键带后缀）。此前直接用纯数字取键恒 miss、永远走最热 fallback。
    val targetMap = if (isMali) maliMap else adrenoMap
    val gpuKey = if (gpuModel.isBlank() || gpuModel == "unknown") ""
        else if (isMali) "Mali-G$gpuModel" else "Adreno-$gpuModel"
    val matched = when {
        gpuKey.isBlank() -> null
        targetMap[gpuKey] != null -> targetMap[gpuKey]
        else -> targetMap.entries.firstOrNull { it.key.contains(gpuKey, ignoreCase = true) }?.value
    }
    val fallback = if (isMali) maliMap.values.maxByOrNull { it.sumOf { c -> c.count } } ?: emptyList()
        else adrenoMap.values.maxByOrNull { it.sumOf { c -> c.count } } ?: emptyList()
    val recs = matched ?: fallback
    val matchIsExact = matched != null && gpuKey.isNotBlank()
    if (recs.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.winlator_config_recs_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.winlator_config_recs_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val gpuLabel = when {
                gpuModel.isBlank() || gpuModel == "unknown" -> if (isMali) "Mali" else "Adreno"
                isMali -> "Mali-G$gpuModel"
                else -> "Adreno $gpuModel"
            }
            Text(
                if (matchIsExact) "当前 GPU：$gpuLabel · 本机型号配置"
                else "当前 GPU：$gpuLabel · 暂无该型号数据，显示社区最热",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(8.dp))
            recs.take(3).forEachIndexed { idx, rec ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (idx == 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TOP${idx + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                comboText(rec.combo),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "社区配置 ${rec.count} 次 · 占比 ${rec.share}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (matched == null) "当前 GPU 无独立统计，显示全库最热组合" else "数据来源：bannerlator-game-configs + Winlator-Mali-Community-Configs（自动聚合）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 官方/社区入门配置层：按 GPU 厂商给出原则性起点（渲染器/Wrapper/分辨率等）
            val official = remember(gpuModel, isMali) { ConfigRecommendStore.find(gpuModel, context)?.official.orEmpty() }
            if (official.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                Text(
                    appString(R.string.winlator_official_recs_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                official.forEach { rec ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(rec.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (rec.summary.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(rec.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            rec.settings.forEach { (k, v) ->
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        k,
                                        modifier = Modifier.width(88.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(v, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPickGuideCard(adrenoSeries: String?, driverMode: Boolean, deviceInfo: DeviceInfo? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(appString(R.string.quick_pick), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (driverMode) appString(R.string.quick_pick_driver_subtitle)
                        else if (adrenoSeries != null) appString(R.string.starting_points_adreno, adrenoSeries)
                        else appString(R.string.good_starting_points),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 智能设备识别：当前设备型号 + SoC 品牌徽章（文字+品牌色，避免官方 logo 版权）
            if (deviceInfo != null) {
                val socName = deviceInfo.socName
                val brand = if (socName.isNotBlank()) detectSocBrand(socName)
                    else detectGpuSocBrand(deviceInfo.gpuModel, deviceInfo.gpuVendor)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SocBrandBadge(brand = brand, text = brand.name)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOf(deviceInfo.deviceBrand, deviceInfo.deviceModel)
                            .filter { it.isNotBlank() }.distinct().joinToString(" ")
                            .ifBlank { socName.ifBlank { "—" } },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 驱动指南只列驱动相关快速选择，避免与组件指南内容重复
            // 按 GPU 厂商给驱动推荐：Adreno→Turnip、Mali→PanVK、PowerVR→厂商/实验
            val vendor = deviceInfo?.gpuVendor
            if (driverMode) {
                when (vendor) {
                    GpuVendor.ADRENO -> {
                        QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                        QuickPickRow("Turnip (Mesa)", "Adreno 专用开源驱动，推荐首选")
                        QuickPickRow("AdrenoTools", "驱动包实测：加载/扩展/实例对比")
                    }
                    GpuVendor.MALI -> {
                        QuickPickRow("PanVK", "Mali 专用 Vulkan 驱动（实验）")
                        QuickPickRow("厂商驱动", "优先使用系统自带 Mali 驱动")
                    }
                    GpuVendor.POWERVR -> {
                        QuickPickRow("厂商驱动", "PowerVR 无开源驱动，用系统自带")
                    }
                    else -> {
                        QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                        QuickPickRow("Turnip (Mesa)", "Adreno 专用开源驱动，推荐首选")
                    }
                }
            } else {
                QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                if (!driverMode) {
                    QuickPickRow("DirectX 8–11", appString(R.string.quick_dx8_11))
                    QuickPickRow("DirectX 12", appString(R.string.quick_dx12))
                    QuickPickRow("Direct3D 3–7", appString(R.string.quick_d3d3_7))
                    QuickPickRow(appString(R.string.windows_runtime), appString(R.string.quick_windows))
                    QuickPickRow(appString(R.string.x86_64_arm64), appString(R.string.quick_cpu))
                }
            }
        }
    }
}

@Composable
private fun QuickPickRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun GuideTopicCard(
    topic: GuideTopic,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Fusion.SurfaceAlt
                ) {
                    // 图标本身给品牌色：底色已是中性灰，图标再用灰会整片糊在一起，
                    // 八个主题卡长得一模一样，扫描时找不到落点
                    Icon(
                        topic.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = Fusion.Primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appString(topic.titleRes), style = MaterialTheme.typography.titleMedium)
                    Text(
                        appString(topic.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) appString(R.string.collapse) else appString(R.string.expand)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 分类与徽章是标签，不是结论；收起时挂在卡片上只是白占一行高度
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(onClick = {}, label = { Text(appString(topic.categoryRes)) })
                        AssistChip(onClick = {}, label = { Text(appString(topic.badgeRes)) })
                    }
                    HorizontalDivider()
                    GuideDetailBlock(appString(R.string.what_it_is), appString(topic.whatItIsRes), Icons.Default.Info)
                    GuideDetailBlock(appString(R.string.try_first), appString(topic.tryFirstRes), Icons.Default.PlayArrow)
                    GuideDetailBlock(appString(R.string.best_for), appString(topic.bestForRes), Icons.Default.CheckCircle)
                    GuideDetailBlock(appString(R.string.when_to_switch), appString(topic.switchWhenRes), Icons.Default.SwapHoriz)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            appString(R.string.useful_notes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        topic.tipRes.forEach { tipRes ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    appString(tipRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (topic.stepsRes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                appString(R.string.useful_steps),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            topic.stepsRes.forEachIndexed { index, stepRes ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        appString(stepRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDetailBlock(label: String, text: String, icon: ImageVector, extra: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (extra != null) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        extra,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** 设备指南：解释设备信息字段、跑分口径、SoC 知识库与驱动路线推荐 */
@Composable
internal fun DeviceGuideDialog(
    onDismiss: () -> Unit,
    deviceInfo: DeviceInfo? = null,
    vulkanPayload: VulkanInfoPayload? = null,
    benchmarkResult: BenchmarkResult? = null
) {
    val guideContext = LocalContext.current
    // 动态附注：全部来自本机实测/本地规格库，不编造
    val vulkanExtra = vulkanPayload?.devices?.firstOrNull()?.let { d ->
        "本机实测：Vulkan ${d.apiVersion} · ${d.driverName}（${d.extensions.size} 个扩展）"
    } ?: "本机 Vulkan 信息暂未采集"
    val benchExtra = when {
        benchmarkResult?.success == true -> "本机实测总分 ${benchmarkResult.totalScore}（填充带宽 ${benchmarkResult.fillBandwidthGBs} GB/s）"
        benchmarkResult != null -> "实测受 App 进程环境限制（SELinux/容器），性能请在模拟器内验证"
        else -> "尚未实测；在「实测」里跑一次驱动实测后自动填充"
    }
    val socExtra = if (deviceInfo != null) {
        val spec = SocSpecStore.find(deviceInfo.socName, guideContext)
        val bench = BenchEngine.resolve(deviceInfo.socName, deviceInfo.gpuModel, guideContext)
        when {
            spec != null && bench?.antutuTotal != null ->
                "规格命中：${spec.totalCores} 核 · 最高 ${formatFreq(spec.maxFreqGhZ)}；安兔兔 ${bench.antutuTotal}"
            spec != null ->
                "规格命中：${spec.totalCores} 核 · 最高 ${formatFreq(spec.maxFreqGhZ)}（含各簇频率）"
            bench?.antutuTotal != null ->
                "跑分命中：安兔兔 ${bench.antutuTotal}（规格库暂无 CPU 明细）"
            else -> "规格库未收录当前芯片，按平台通用信息展示"
        }
    } else "未检测到设备信息"
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(appString(R.string.device_guide_title), style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    appString(R.string.device_guide_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 智能：当前设备摘要卡（品牌/型号 + SoC 品牌徽章）
                if (deviceInfo != null) {
                    val socName = deviceInfo.socName
                    val brand = if (socName.isNotBlank()) detectSocBrand(socName)
                        else detectGpuSocBrand(deviceInfo.gpuModel, deviceInfo.gpuVendor)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                listOf(deviceInfo.deviceBrand, deviceInfo.deviceModel)
                                    .filter { it.isNotBlank() }.distinct().joinToString(" ")
                                    .ifBlank { socName.ifBlank { "—" } },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(brand.color).copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(brand.label, style = MaterialTheme.typography.labelMedium, color = Color(brand.color))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    listOfNotNull(
                                        socName.ifBlank { null },
                                        deviceInfo.gpuModel.takeIf { it.isNotBlank() },
                                        deviceInfo.ram.takeIf { it.isNotBlank() }
                                    ).joinToString(" · ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                GuideDetailBlock(
                    appString(R.string.device_guide_vulkan),
                    appString(R.string.device_guide_vulkan_desc),
                    Icons.Default.Info,
                    extra = vulkanExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_bench),
                    appString(R.string.device_guide_bench_desc),
                    Icons.Default.Speed,
                    extra = benchExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_soc),
                    appString(R.string.device_guide_soc_desc),
                    Icons.Default.Memory,
                    extra = socExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_route),
                    appString(R.string.device_guide_route_desc),
                    Icons.Default.Navigation
                )
                DeviceGuideComponentRec(deviceInfo)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(appString(R.string.ok))
                }
            }
        }
    }
}

/** 设备指南-组件类型推荐：按当前 GPU 厂商给出组件组合与官方发布日志要点（动态生成，无官方 logo 依赖） */
@Composable
private fun DeviceGuideComponentRec(deviceInfo: DeviceInfo?) {
    val ctx = LocalContext.current
    val vendor = deviceInfo?.gpuVendor
    val isAdreno = vendor == GpuVendor.ADRENO
    val isMali = vendor == GpuVendor.MALI
    // 真实驱动匹配：data/driver-registry.json 按当前 GPU 评分排序，优先展示推荐条目
    val registryDrivers = remember(deviceInfo) {
        DriverRegistryRepository.loadFromAssets(ctx)
            ?.let { DriverRegistryRepository.match(deviceInfo, it) }
            ?.sortedByDescending { it.recommended }
            ?.take(3)
            ?: emptyList()
    }
    val recs = when {
        registryDrivers.isNotEmpty() -> registryDrivers.map { m ->
            val e = m.entry
            val tag = if (e.latestTag.isNotBlank()) "（${e.latestTag}）" else ""
            "驱动：${e.name}$tag — ${e.description}${if (m.recommended) "（按当前设备推荐）" else ""}"
        } + when {
            isAdreno -> listOf(
                "DXVK 2.x：D3D9/10/11 转译层，Adreno 7xx 推荐 2.5+；VKD3D-Proton 2.x 处理 D3D12",
                "Wine/Proton：WOW64 + Box64 基础组件，优先用各源最新发布"
            )
            isMali -> listOf(
                "DXVK 2.x + VKD3D-Proton：转译层与 PanVK 搭配，官方日志含 Mali 修复",
                "Box64/WOWBox64：x86 转译基础组件，按模拟器内置版本对应选择"
            )
            else -> listOf(
                "组件：DXVK 2.x 转译 D3D9/10/11，VKD3D-Proton 转译 D3D12，按游戏 API 需求安装",
                "Wine/Proton + Box64：Winlator 运行环境基础，跟随组件源最新版本"
            )
        }
        else -> when {
            isAdreno -> listOf(
                "驱动路线：Turnip（Mesa 开源）为 Winlator 首选，AdrenoTools 自定义驱动可解锁更高版本；备选高通官方驱动",
                "DXVK 2.x：D3D9/10/11 转译层，官方日志持续改进动态渲染与内存管理，Adreno 7xx 推荐 2.5+",
                "VKD3D-Proton 2.x：D3D12 转译层，支持光追后备；游戏要求 D3D12 时安装",
                "Wine/Proton：WOW64 组件配合 Box64 使用，优先用各源最新发布"
            )
            isMali -> listOf(
                "驱动路线：PanVK（Mesa Panfrost 开源 Mali 驱动）首选，备选 Mali 厂商驱动",
                "DXVK 2.x + VKD3D-Proton：转译层与 PanVK 搭配，官方日志含 Mali 相关修复",
                "Box64/WOWBox64：x86 转译基础组件，按模拟器内置版本对应选择"
            )
            else -> listOf(
                "驱动：按 GPU 厂商选择对应开源驱动（Adreno→Turnip，Mali→PanVK），或厂商官方驱动",
                "组件：DXVK 2.x 转译 D3D9/10/11，VKD3D-Proton 转译 D3D12，两者按游戏 API 需求安装",
                "Wine/Proton + Box64：Winlator 运行环境基础，建议跟随组件源最新版本"
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "组件类型推荐（按当前设备）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            recs.forEach { r ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        r,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
