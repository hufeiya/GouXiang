package com.neethu.gouxiang.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** 检查录音权限；未授权时请求权限或引导用户前往设置。返回 true 才能开始录音。 */
@Composable
fun rememberMicrophonePermissionRequest(): () -> Boolean {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            val activity = context.findActivity()
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showSettings = true
            } else {
                Toast.makeText(context, "需要麦克风权限才能按住说话", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("开启麦克风权限") },
            text = { Text("按住说话需要麦克风权限。请在应用设置中允许使用麦克风，返回后重新按住说话。") },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } },
        )
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
