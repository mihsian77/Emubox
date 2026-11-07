package com.emubox.app.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
