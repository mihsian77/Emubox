package com.emubox.app

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.template.ConclusionCard
import com.emubox.app.ui.template.ConclusionTone
import com.emubox.app.ui.template.FusionCard
import com.emubox.app.ui.template.SpecItem
import com.emubox.app.ui.template.SpecStrip
import com.emubox.app.ui.theme.Fusion
import java.util.*


// ---------- Driver hub / index ----------
internal data class HubSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val latest: String,
    val source: String,
    val icon: ImageVector
)


/**
 * 折叠状态的来源提示：来源名 + 距上次发布的时间。
 *
 * 驱动区分区默认折叠，一屏要放五六个分区。折叠时只留这两项，
 * 换源后不展开也能看出这个源多久没发新版。一周内发布的用主题色强调，
 * 更早的用次要色，避免满屏都在喊「新」。
 */
@Composable
internal fun CollapsedSourceHint(name: String, latestPublishedAt: String?) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 源名可能很长（the412banner 的整合包名带一串包含项），不限宽会把
        // 右侧「更新时间」和展开箭头挤出屏幕
        modifier = Modifier.widthIn(max = 104.dp).padding(end = 6.dp)
    )
    if (!latestPublishedAt.isNullOrBlank()) {
        Spacer(Modifier.width(2.dp))
        val relTime = formatRelativeTime(latestPublishedAt)
        // formatRelativeTime 返回「刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy-MM-dd」
        // 超一周直接给日期，所以只有一周内才算「新鲜」高亮。
        val fresh = relTime == "刚刚" || (relTime.endsWith("前") && !relTime.contains("天")
            && !relTime.contains("个月"))
        Text(
            "· $relTime",
            style = MaterialTheme.typography.labelSmall,
            color = if (fresh) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「给谁用 · 怎么装 · 注意」三行说明块。
 *
 * 驱动和组件页以前只给一行作者简介（「稳定版 Turnip 构建」这种），
 * 用户还得自己判断这包适不适合自己的机器、下完往哪儿放。
 * 这里把三件事并排摊开：适用机型/GPU、按顺序的安装步骤、什么时候别用。
 * 文案来自 assets/source-profiles.json，读不到就整块不渲染。
 */
@Composable
internal fun UsageBlock(profile: SourceProfile?, modifier: Modifier = Modifier) {
    if (profile == null || profile.isEmpty) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (profile.fit.isNotBlank()) {
                UsageRow("给谁用", profile.fit, MaterialTheme.colorScheme.onSurface)
            }
            if (profile.how.isNotEmpty()) {
                UsageRow(
                    "怎么装",
                    profile.how.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                    MaterialTheme.colorScheme.onSurface
                )
            }
            if (profile.caution.isNotBlank()) {
                UsageRow("注意", profile.caution, MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(top = 1.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 专区大标题（驱动专区 / 组件专区）。
 *
 * 曾经右侧挂一个「实时来源 / 内置兜底」的 chip：数据来自远端还是内置。
 * 这个信息对用户没有决策价值——内置源同样是可用来源，看到「内置兜底」
 * 反而以为是降级状态。来源与更新时间已经在各分区的来源选择器里能查到，
 * 标题行只留标题本身。
 */
@Composable
internal fun RegionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Fusion.OnSurface
        )
    }
}

@Composable
internal fun DeviceConclusionBlock(
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    route: DriverRouteRecommendation?,
) {
    val context = LocalContext.current
    val gpuIsAdreno = deviceInfo?.gpuVendor == GpuVendor.ADRENO
    val resolvedSoc = remember(deviceInfo?.gpuModel, deviceInfo?.socName, gpuIsAdreno) {
        runCatching {
            SocResolver.resolveCurrent(
                context, deviceInfo?.gpuModel.orEmpty(), deviceInfo?.socName.orEmpty(),
                gpuIsAdreno
            )
        }.getOrNull()
    }

    // 芯片名只出现一次：中文营销名优先，无映射时退回原名
    val rawSoc = deviceInfo?.socName?.takeIf { it.isNotBlank() && !it.contains("未知") }
    val cnName = (resolvedSoc?.displayName?.takeIf { it.isNotBlank() }
        ?: deviceInfo?.let { socChineseName(it, context) }).orEmpty()
    val socPrimary = when {
        cnName.isNotBlank() && cnName != rawSoc -> cnName
        rawSoc != null -> rawSoc
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isLoading) {
            FusionCard {
                Text(
                    appString(R.string.detecting_hardware),
                    fontSize = 14.sp,
                    color = Fusion.OnSurfaceDim,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else if (deviceInfo == null) {
            FusionCard {
                Text(
                    appString(R.string.hardware_unavailable),
                    fontSize = 14.sp,
                    color = Fusion.OnSurfaceDim,
                )
            }
        } else {
            // 结论先行：先说「能跑成什么样」，参数只是支撑证据
            val tone = when (route?.turnipSupport) {
                TurnipSupport.FULL -> ConclusionTone.GOOD
                TurnipSupport.PARTIAL -> ConclusionTone.WARN
                TurnipSupport.NONE -> ConclusionTone.BAD
                null -> ConclusionTone.NEUTRAL
            }
            val headline = when (route?.turnipSupport) {
                TurnipSupport.FULL -> appString(R.string.device_conclusion_full)
                TurnipSupport.PARTIAL -> appString(R.string.device_conclusion_partial)
                TurnipSupport.NONE -> appString(R.string.device_conclusion_none)
                null -> appString(R.string.device_conclusion_unknown)
            }
            val badge = when (route?.turnipSupport) {
                TurnipSupport.FULL -> appString(R.string.device_badge_recommended)
                TurnipSupport.PARTIAL -> appString(R.string.device_badge_limited)
                TurnipSupport.NONE -> appString(R.string.device_badge_caution)
                null -> null
            }
            ConclusionCard(
                headline = headline,
                evidence = listOfNotNull(
                    socPrimary,
                    listOf(deviceInfo.deviceBrand, deviceMarketingName(deviceInfo.deviceModel, context))
                        .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null },
                ).joinToString(" · "),
                badge = badge,
                tone = tone,
            )
            // 参数横排：三项一行放完，不做「一卡一格」
            SpecStrip(
                listOf(
                    SpecItem(appString(R.string.android_label), deviceInfo.androidVersion),
                    SpecItem(appString(R.string.ram_label), deviceInfo.ram),
                    SpecItem(appString(R.string.gpu_label), gpuDisplayName(deviceInfo)),
                )
            )
        }
    }
}

/**
 * GPU 显示名。
 *
 * detectGpuModel 只返回数字（740/720），Mali、Immortalis 直接拼 "Adreno" 会误导，
 * 所以先从 renderer 里取完整型号（Mali-G720 / Immortalis-G720 / Mali-G57），
 * 取不到再按厂商拼前缀——不能默认拼 Adreno：展锐的 Mali-G57 曾显示成「Adreno 57」。
 */
internal fun gpuDisplayName(info: DeviceInfo): String {
    val renderer = info.gpuRenderer.orEmpty()
    // G 后可能是两位数（G31/G52/G57/G76），写死 3-4 位会把整个 Mali 分支漏掉
    Regex("(?:Mali|Immortalis)[\\s-]*G\\d{2,4}[A-Z]*", RegexOption.IGNORE_CASE)
        .find(renderer)?.value?.replaceFirstChar { it.uppercase() }
        ?.let { return it }
    Regex("Xclipse[\\s-]*\\d{3,4}", RegexOption.IGNORE_CASE)
        .find(renderer)?.value?.let { return it }
    val model = info.gpuModel
    return when {
        model.isBlank() || model == "unknown" -> renderer.ifBlank { "—" }
        // gpuModel 有时已是「Adreno 750」全名，直接拼会变成「Adreno Adreno 750」
        model.contains("Adreno", true) -> model
        info.gpuVendor == GpuVendor.ADRENO -> "Adreno $model"
        info.gpuVendor == GpuVendor.MALI -> "Mali-G$model"
        info.gpuVendor == GpuVendor.XCLIPSE -> "Xclipse $model"
        else -> model
    }
}

/** 厂商官方徽章资源（大图版）：识别失败返回 null（回退通用手机图标）。 */
private fun socBrandIconRes(brandName: String): Int? = when (brandName) {
    "Qualcomm", "高通" -> R.drawable.ic_soc_qualcomm
    "MediaTek", "联发科" -> R.drawable.ic_soc_mediatek
    "Samsung", "三星" -> R.drawable.ic_soc_samsung
    "Huawei", "华为" -> R.drawable.ic_soc_huawei
    "Google", "谷歌" -> R.drawable.ic_soc_google
    "ARM" -> R.drawable.ic_soc_arm
    else -> null
}

/**
 * 设备代号→营销名映射表只解析一次。
 *
 * 原实现把 assets 读取写在 deviceMarketingName() 里，而该函数在 DeviceSummaryCard
 * 的 Text 参数里调用——每次重组（滚动、切主题、进度刷新）都会读一遍文件。
 * 设备页一屏至少重组成百次，等于每帧一次磁盘 IO。
 */
internal object DeviceModelNameStore {
    @Volatile
    private var map: Map<String, String>? = null

    fun lookup(context: Context, model: String): String {
        val table = map ?: synchronized(this) {
            map ?: runCatching {
                val root = org.json.JSONObject(
                    context.assets.open("device_models.json").bufferedReader().use { it.readText() }
                )
                val models = root.optJSONObject("models")
                buildMap<String, String> {
                    if (models != null) {
                        val keys = models.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            put(k, models.optString(k))
                        }
                    }
                }
            }.getOrDefault(emptyMap()).also { map = it }
        }
        return table[model]?.takeIf { it.isNotBlank() } ?: model
    }
}

/** 设备代号→营销名识别（vivo V2338A → iQOO Neo9）。映射表 assets/device_models.json，未收录返回原代号。 */
internal fun deviceMarketingName(model: String, context: Context): String {
    if (model.isBlank()) return ""
    return DeviceModelNameStore.lookup(context, model)
}

/** 厂商徽章英文官方原名：高通→Qualcomm、联发科→MediaTek、三星→Samsung、华为→Huawei、谷歌→Google。 */
private fun socBrandEnglishName(name: String): String = when (name) {
    "高通", "Qualcomm" -> "Qualcomm"
    "联发科", "MediaTek" -> "MediaTek"
    "三星", "Samsung" -> "Samsung"
    "华为", "Huawei" -> "Huawei"
    "谷歌", "Google" -> "Google"
    "ARM", "Arm" -> "ARM"
    else -> name
}

/** 中文 SoC 名：知识库条目名优先（骁龙 8 Gen 2），内置中英映射兜底。 */
private fun socChineseName(deviceInfo: DeviceInfo, context: Context): String {
    if (deviceInfo.socName.isBlank()) return "—"
    // 已是中文（骁龙…/天玑…/麒麟…）直接返回
    if (deviceInfo.socName.any { it in "骁龙天玑麒麟" }) return deviceInfo.socName
    SocKnowledgeStore.find(deviceInfo.socName, deviceInfo.gpuModel, context)?.name?.let { return it }
    return deviceInfo.socName
}

@Composable
internal fun SocBrandBadge(brand: SocBrand, text: String, modifier: Modifier = Modifier) {
    // 本地内置 PNG 图标（Simple Icons 转制），显示厂商全名；未收录厂商回退纯文字徽章
    val iconRes = when (brand.name) {
        "Qualcomm", "高通" -> R.drawable.ic_soc_qualcomm
        "MediaTek", "联发科" -> R.drawable.ic_soc_mediatek
        "Samsung", "三星" -> R.drawable.ic_soc_samsung
        "Huawei", "华为" -> R.drawable.ic_soc_huawei
        "Google", "谷歌" -> R.drawable.ic_soc_google
        "ARM" -> R.drawable.ic_soc_arm
        else -> null
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(brand.color).copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color(brand.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeviceStat(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 根据 GPU 信息给出 Winlator 驱动路线推荐。
 * 参考 Winlator 官方文档：Turnip 仅 Adreno 6xx+，不支持型号用 VirGL，Mali 用 Zink/Gladio。
 */
internal data class DriverRouteRecommendation(
    val primaryRoute: String,        // 主推驱动路线
    val secondaryRoute: String,       // 备选
    val turnipSupport: TurnipSupport, // Turnip 支持状态
    val note: String                  // 简短说明
)

internal enum class TurnipSupport { FULL, PARTIAL, NONE }

internal fun recommendDriverRoute(deviceInfo: DeviceInfo): DriverRouteRecommendation {
    val model = deviceInfo.gpuModel
    val vendor = deviceInfo.gpuVendor
    val series = deviceInfo.adrenoSeries

    return when (vendor) {
        GpuVendor.ADRENO -> {
            // Turnip 官方弱支持的 Adreno 型号（735/732/720/710/613 等）
            val weakModels = setOf("735", "732", "720", "710", "613", "612", "610")
            when {
                weakModels.contains(model) -> DriverRouteRecommendation(
                    primaryRoute = "VirGL",
                    secondaryRoute = "Turnip（可能不稳定）",
                    turnipSupport = TurnipSupport.PARTIAL,
                    note = "该 Adreno 型号 Turnip 支持有限，DX9 游戏用 VirGL 更稳，3D 游戏可尝试 Turnip"
                )
                series == "8xx" -> DriverRouteRecommendation(
                    primaryRoute = "Turnip + Zink",
                    secondaryRoute = "Eden 专用 Turnip（部分仓库）",
                    turnipSupport = TurnipSupport.FULL,
                    note = "Adreno 8xx 为最新架构，推荐最新 Turnip 构建，部分驱动针对 Eden 模拟器优化"
                )
                else -> DriverRouteRecommendation(
                    primaryRoute = "Turnip + Zink",
                    secondaryRoute = "VirGL（DX9 轻量游戏）",
                    turnipSupport = TurnipSupport.FULL,
                    note = "Adreno 6xx/7xx 主流型号，Turnip + Zink 是 Winlator 最佳性能组合，支持 DXVK/VKD3D"
                )
            }
        }
        GpuVendor.MALI -> DriverRouteRecommendation(
            primaryRoute = "Zink / Gladio",
            secondaryRoute = "PanVK（部分型号实验性）",
            turnipSupport = TurnipSupport.NONE,
            note = "Mali GPU 不支持 Turnip，Winlator 用 Zink 或 Gladio 翻译层，兼容性和性能弱于 Adreno+Turnip"
        )
        GpuVendor.XCLIPSE -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "无成熟方案",
            turnipSupport = TurnipSupport.NONE,
            note = "Exynos Xclipse（AMD RDNA）在 Android 上无成熟 Vulkan 驱动，Winlator 兼容性差"
        )
        GpuVendor.POWERVR -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "Zink（实验性）",
            turnipSupport = TurnipSupport.NONE,
            note = "PowerVR GPU 无 Turnip 支持，VirGL 是唯一较稳定的选择"
        )
        GpuVendor.UNKNOWN -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "尝试 Zink",
            turnipSupport = TurnipSupport.NONE,
            note = "无法识别 GPU 厂商，建议先用 VirGL 兜底，再根据实际情况尝试其他驱动"
        )
    }
}
