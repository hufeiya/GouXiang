package com.neethu.gouxiang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题:固定亮色 + 白底黑字(对齐 02 消息页)/ 黑底底栏(01)。
 * 关闭 dynamicColor:还原基准视觉优先。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFFE84D8A),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE84D8A),
    background = Color(0xFF0B0B0B),
    onBackground = Color.White,
)

@Composable
fun GouXiangTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography,
        content = content,
    )
}
