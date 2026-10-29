package com.neethu.gouxiang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 占位页:发现 / 创作AI+ / 我的(范围说明:三 tab 用占位页)。
 */
@Composable
fun PlaceholderPage(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        // statusBarsPadding(沉浸式改动):Scaffold 不再消费状态栏 inset 后,
        // 占位页内容不能顶进状态栏。
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color(0xFFB0B0B0), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                color = Color(0xFF666666),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFFF2F2F2), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(text = "页面建设中,敬请期待", fontSize = 12.sp, color = Color(0xFF777777))
            }
        }
    }
}

/** 占位页各自需要的图标。 */
object PlaceholderIcons {
    val Discover: ImageVector get() = Icons.Outlined.Explore
    val Create: ImageVector get() = Icons.Filled.Build
    val Profile: ImageVector get() = Icons.Filled.Person
}
