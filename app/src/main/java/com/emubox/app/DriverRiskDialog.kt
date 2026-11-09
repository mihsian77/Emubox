package com.emubox.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 第三方驱动的首次使用确认。
 *
 * 驱动包最终会被 dlopen 成原生代码，等价于「安装一个能跑任意代码的程序」。
 * 这里不是法律免责声明，而是把风险事实说清楚一次：用户确认后不再打扰。
 */
@Composable
fun DriverRiskDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.driver_risk_title)) },
        text = {
            Text(
                appString(R.string.driver_risk_body),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(appString(R.string.driver_risk_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(appString(R.string.storage_dialog_later))
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
