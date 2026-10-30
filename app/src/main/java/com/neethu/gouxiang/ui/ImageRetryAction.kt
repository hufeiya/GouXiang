package com.neethu.gouxiang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable
fun ImageRetryAction(image: ImageGenerationState?, enabled: Boolean, color: Color, onRetry: (ImageGenerationState) -> Unit) {
    if (image == null || !enabled) return
    when (image.status) {
        "pending" -> Text("配图生成中…", color = color, fontSize = 12.sp)
        "failed", "skipped" -> Column {
            Text(if (image.status == "failed") "配图生成失败，文字已保存" else "未生成配图", color = color, fontSize = 12.sp)
            Text(if (image.status == "failed") "重试配图" else "生成配图", color = color, fontSize = 12.sp,
                modifier = Modifier.clickable { onRetry(image) })
        }
    }
}
