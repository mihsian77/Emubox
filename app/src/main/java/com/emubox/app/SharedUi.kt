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


@Composable
internal fun FusionFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) Brush.linearGradient(colors = listOf(Fusion.GradientStart, Fusion.GradientEnd))
                else androidx.compose.ui.graphics.SolidColor(Fusion.SurfaceAlt)
            )
            .border(1.dp, if (selected) Color.Transparent else Fusion.BorderAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) Color.White else Fusion.OnSurfaceDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun FusionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 副色原先写死深色 0xFF1C2030：亮色主题下搜索条右半截会变成一块深灰板，
            // 与浅色页面完全割裂。改为跟随当前色板的 surface，亮/暗两套都自洽。
            .background(Brush.linearGradient(colors = listOf(Fusion.SurfaceAlt, Fusion.Surface)))
            .border(1.dp, Fusion.BorderAlt, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Fusion.OnSurfaceDim,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Fusion.OnSurface, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Fusion.GradientStart),
            modifier = Modifier.weight(1f)
        ) { inner ->
            if (query.isEmpty()) {
                Text(placeholder, color = Fusion.OnSurfaceDim, fontSize = 14.sp)
            }
            inner()
        }
    }
}
