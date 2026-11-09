package com.emubox.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 存储权限缺失时的全局提示条。
 *
 * 放在主 Scaffold 的 topBar 位置：任何分区都能看到，不会出现
 * 「用户不知道为什么会失败」的情况。
 */
@Composable
fun StoragePermissionBanner(onGrant: () -> Unit) {
    Surface(
        color = Color(0x33FFB020),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB020)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = appString(R.string.storage_perm_banner_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onGrant) {
                Text(appString(R.string.storage_perm_banner_action))
            }
        }
    }
}

/**
 * 授权失败说明对话框：从系统设置页返回仍未授权时弹出。
 *
 * 给三条出路：重新去设置、改用 SAF 选文件夹（不依赖系统权限）、稍后。
 */
@Composable
fun StoragePermissionDialog(
    onGrant: () -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.storage_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    appString(R.string.storage_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    appString(R.string.storage_dialog_body2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(appString(R.string.storage_dialog_grant))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChooseFolder) {
                    Text(appString(R.string.storage_dialog_saf))
                }
                TextButton(onClick = onDismiss) {
                    Text(appString(R.string.storage_dialog_later))
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
