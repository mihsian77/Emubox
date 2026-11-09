package com.emubox.app.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.theme.Fusion

/*
 * 页面模板组件库（排版规格 v1 的实现层）。
 *
 * 约束来自 docs/ui-layout-spec.md 的五条硬规则：
 *   1. 首屏见内容       → listPageHeader 把「标题/搜索/筛选/计数」压成最多三层
 *   2. 控件不过三       → 头部只提供 搜索 + 一行筛选 + 排序尾插
 *   3. 卡片不套娃       → FusionCard 固定一层底色 + 一层描边，不接受嵌套底色
 *   4. 一屏一任务       → 折叠区统一用 CollapseRow，默认收起
 *   5. 不重复展示       → 结果计数只由 ResultCountRow 输出，页面不自己再写一遍
 *
 * 这里刻意只做「区块级」组件，不接管整页 LazyColumn：现有页面各自持有
 * 滚动容器与状态，整页接管会牵动路由与状态提升，收益不抵风险。
 */

/** 卡片外壳：全 App 唯一允许的卡片底色与描边组合，禁止在内部再叠一层底色。 */
@Composable
fun FusionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Fusion.Surface)
        .border(BorderStroke(1.dp, Fusion.Border), shape)
    Column(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
        content = { Column(Modifier.padding(contentPadding)) { content() } },
    )
}

/**
 * 列表页标题行。
 *
 * @param subtitle 一行副标题，超过一行即截断——副标题是用来交代口径的，不是正文
 */
@Composable
fun PageTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Fusion.Primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Fusion.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Fusion.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** 搜索条：单行，无外框套外框；清空按钮只在有内容时出现。 */
@Composable
fun FusionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // 渐变底而非纯色：纯色细框在深色底上几乎看不出边界，搜索框会「消失」
            .background(Brush.linearGradient(listOf(Fusion.SurfaceAlt, Fusion.Surface)))
            .border(BorderStroke(1.dp, Fusion.BorderAlt), shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Fusion.OnSurfaceDim,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(fontSize = 14.sp, color = Fusion.OnSurface),
            cursorBrush = SolidColor(Fusion.GradientStart),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(placeholder, fontSize = 14.sp, color = Fusion.OnSurfaceDim)
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Fusion.OnSurfaceFaint,
                )
            }
        }
    }
}

/**
 * 一行筛选。横向滚动，永远只占一行高度——分类再多也不许换行去挤结果。
 */
@Composable
fun FilterChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 结果计数 + 尾部动作（收藏开关、视图切换、排序）。
 *
 * 计数是「筛选是否生效」的唯一反馈，列表页必须有且只出现一次。
 */
@Composable
fun ResultCountRow(
    count: Int,
    label: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label(count),
            fontSize = 12.sp,
            color = Fusion.OnSurfaceDim,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/**
 * 折叠条：被降级的次要分区（推荐位、动态、榜单）统一用这个入口，默认收起。
 *
 * @param expanded 由调用方持有状态，避免折叠态在重组时被重置
 */
@Composable
fun CollapseRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Fusion.SurfaceAlt)
            .border(BorderStroke(1.dp, Fusion.BorderAlt), RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Fusion.OnSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Fusion.OnSurfaceFaint,
        )
    }
}

/**
 * 列表页头部：把「标题 / 搜索 / 一行筛选 / 结果计数」一次性带进 LazyColumn。
 *
 * 这是列表型页面的唯一入口写法，页面不要再自己拼这几行。
 * 顺序固定，不接受参数调换——顺序是排版结论，不是可配置项。
 */
fun LazyListScope.detailPageHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    item(key = "tpl_detail_header") {
        PageTitleRow(title = title, subtitle = subtitle, icon = icon, trailing = trailing)
    }
}

/**
 * 结论卡：详情页第一屏的第一块，一句话说清「这台机器能干什么」。
 *
 * 结论必须在参数之前——用户要的是判断，不是跑分。
 *
 * @param headline 结论本身，一句话，不带修饰
 * @param evidence 支撑结论的一行证据（分数 / 型号 / 命中条数）
 * @param badge    右上角短标签（「推荐」「受限」「不可用」）
 */
@Composable
fun ConclusionCard(
    headline: String,
    modifier: Modifier = Modifier,
    evidence: String? = null,
    badge: String? = null,
    tone: ConclusionTone = ConclusionTone.NEUTRAL,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val accent = tone.accent()
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Fusion.HeroStart, Fusion.HeroEnd)))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Fusion.OnSurface,
                )
                if (!evidence.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        evidence,
                        fontSize = 12.sp,
                        color = Fusion.OnSurfaceDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!badge.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.16f)) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
        }
        if (actions != null) {
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

enum class ConclusionTone { GOOD, WARN, BAD, NEUTRAL }

private fun ConclusionTone.accent() = when (this) {
    ConclusionTone.GOOD -> Fusion.Primary
    ConclusionTone.WARN -> Fusion.Amber
    ConclusionTone.BAD -> Fusion.Amber
    ConclusionTone.NEUTRAL -> Fusion.GradientStart
}

/** 关键参数的一项。 */
data class SpecItem(val label: String, val value: String)

/**
 * 关键参数横排：3–4 项一行放完。
 *
 * 详情页不许「一张卡只放一个值」——参数是背景信息，占的空间必须和它的信息量匹配。
 * 超过 4 项请调用方自己取舍，不要换行堆第二排。
 */
@Composable
fun SpecStrip(
    items: List<SpecItem>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Fusion.Surface)
            .border(BorderStroke(1.dp, Fusion.Border), shape)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.take(4).forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(Fusion.Border),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(item.label, fontSize = 10.sp, color = Fusion.OnSurfaceFaint, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    item.value.ifBlank { "—" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Fusion.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 分组标题：小字、弱色。
 *
 * 设置页和详情页的分组靠它分层，不靠给每个分组配一个大图标盒子——
 * 那个盒子会吃掉一整行高度，而它提供的信息量为零。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Fusion.OnSurfaceDim,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Fusion.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

/**
 * 设置分组容器：一层底色，行与行之间用 1dp 分隔。
 *
 * 组内行数不超过 8——超过请再分组（见 docs/ui-layout-spec.md 二·C）。
 */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Fusion.Surface)
            .border(BorderStroke(1.dp, Fusion.Border), shape),
        content = content,
    )
}

/**
 * 设置行：左标题 + 可选副标题，右侧控件。
 *
 * @param showDivider 组内最后一行传 false，避免卡底多一道线
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Fusion.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = Fusion.OnSurfaceFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(1.dp)
                    .background(Fusion.Border),
            )
        }
    }
}

/** 行尾的文字型取值（版本号、路径、当前选项），单行右对齐截断。 */
@Composable
fun SettingValue(text: String, dim: Boolean = true) {
    Text(
        text,
        fontSize = 13.sp,
        color = if (dim) Fusion.OnSurfaceDim else Fusion.OnSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 详情页底部固定操作条。放进 Scaffold 的 bottomBar，或外层 Box 的底部对齐位。
 *
 * 主操作只放一个：详情页的「下一步」是唯一的，多个按钮并列等于没有重点。
 */
@Composable
fun DetailActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(Fusion.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Fusion.Border))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

fun LazyListScope.listPageHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    // 标题与搜索之间的上下文块（例如「当前设备」一行）。
    // 放在搜索之上：搜索结果必须紧跟搜索框，中间不许再夹内容。
    headerContext: (@Composable () -> Unit)? = null,
    query: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    resultCount: Int? = null,
    resultLabel: (@Composable (Int) -> String)? = null,
    filters: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    item(key = "tpl_header") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTitleRow(
                title = title,
                subtitle = subtitle,
                icon = icon,
                trailing = trailing,
            )
            headerContext?.invoke()
            if (query != null && onQueryChange != null) {
                FusionSearchBar(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = searchPlaceholder,
                )
            }
            filters?.invoke()
            if (resultCount != null && resultLabel != null) {
                ResultCountRow(count = resultCount, label = resultLabel)
            }
        }
    }
}

/**
 * 选择型设置行：标题与说明在上，整行控件在下（chips / 分段 / 下拉）。
 *
 * 与 [SettingRow] 的分工：右侧放得下的小控件用 SettingRow，
 * 放不下需要独占一行的选择器用这个。
 */
@Composable
fun SettingChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(title, fontSize = 14.sp, color = Fusion.OnSurface)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Fusion.OnSurfaceFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(1.dp)
                    .background(Fusion.Border),
            )
        }
    }
}

/**
 * 页内子分区切换条（排版规格 v2 的核心件）。
 *
 * 动机：分区页原来把所有模块竖排成一条长列表，新闻被压到最底部，用户想看最后
 * 一块内容必须一路滑到底，首屏也全是折叠条。改成「一屏一个子分区」后，
 * 单页长度降到 2~4 屏，资讯成为独立入口而不是页尾残余。
 *
 * 放在 LazyColumn 的 stickyHeader 里，滚动时保持可见，随时可切。
 *
 * @param tabs 子分区标题（顺序即展示顺序，第一项为默认）
 * @param selected 当前下标
 */
@Composable
fun SectionTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Fusion.Background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Fusion.Primary.copy(alpha = 0.16f) else Fusion.SurfaceAlt)
                    .border(
                        BorderStroke(1.dp, if (active) Fusion.Primary else Fusion.BorderAlt),
                        RoundedCornerShape(50),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) Fusion.Primary else Fusion.OnSurfaceFaint,
                    maxLines = 1,
                )
            }
        }
    }
}
