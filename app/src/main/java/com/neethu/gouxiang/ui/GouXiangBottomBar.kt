package com.neethu.gouxiang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.ui.nav.BottomTab

/**
 * 底栏 5 格(01 截图):首页(电视脸)/ 发现(罗盘)/ 创作AI+(白描方框+AI)/ 消息(圆泡+角标)/ 我的(人形)。
 */
@Composable
fun GouXiangBottomBar(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    messageBadge: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0B0B))
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomItem(BottomTab.HOME, selected == BottomTab.HOME, onSelect)
            BottomItem(BottomTab.DISCOVER, selected == BottomTab.DISCOVER, onSelect)
            CreateAiPlusItem(onSelect)
            BottomItem(BottomTab.MESSAGES, selected == BottomTab.MESSAGES, onSelect, badge = messageBadge)
            BottomItem(BottomTab.PROFILE, selected == BottomTab.PROFILE, onSelect)
        }
    }
}

@Composable
private fun RowScope.BottomItem(
    tab: BottomTab,
    selected: Boolean,
    onSelect: (BottomTab) -> Unit,
    badge: Int = 0,
) {
    val icon: ImageVector = if (selected) selectedIcon(tab) else unselectedIcon(tab)
    val tint = if (selected) Color.White else Color(0xFF9A9A9A)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable { onSelect(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Icon(icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(26.dp))
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-2).dp)
                        .size(16.dp)
                        .background(Color(0xFFFA3E3E), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge.coerceAtMost(99).toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 中间「创作AI+」:白色圆角方框描边 + 中央 + 号 + 右上角 AI 角标。 */
@Composable
private fun RowScope.CreateAiPlusItem(onSelect: (BottomTab) -> Unit) {
    Box(
        modifier = Modifier
            .weight(1.2f)
            .fillMaxSize()
            .clickable { onSelect(BottomTab.CREATE) },
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Canvas(modifier = Modifier.size(width = 52.dp, height = 34.dp)) {
                val stroke = 3.dp.toPx()
                val r = 10.dp.toPx()
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
                val cx = size.width / 2
                val cy = size.height / 2
                val arm = 7.dp.toPx()
                drawLine(
                    Color.White,
                    Offset(cx - arm, cy),
                    Offset(cx + arm, cy),
                    strokeWidth = stroke,
                )
                drawLine(
                    Color.White,
                    Offset(cx, cy - arm),
                    Offset(cx, cy + arm),
                    strokeWidth = stroke,
                )
            }
            Text(
                "AI",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-4).dp),
            )
        }
    }
}

private fun selectedIcon(tab: BottomTab): ImageVector = when (tab) {
    BottomTab.HOME -> Icons.Filled.Home
    BottomTab.DISCOVER -> Icons.Filled.Explore
    BottomTab.CREATE -> Icons.Filled.Explore
    BottomTab.MESSAGES -> Icons.Filled.ChatBubble
    BottomTab.PROFILE -> Icons.Filled.Person
}

private fun unselectedIcon(tab: BottomTab): ImageVector = when (tab) {
    BottomTab.HOME -> Icons.Outlined.Home
    BottomTab.DISCOVER -> Icons.Outlined.Explore
    BottomTab.CREATE -> Icons.Outlined.Explore
    BottomTab.MESSAGES -> Icons.Outlined.ChatBubbleOutline
    BottomTab.PROFILE -> Icons.Outlined.Person
}
