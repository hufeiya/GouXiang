package com.neethu.gouxiang.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.chat.DialogMode
import com.neethu.gouxiang.chat.ReplyLength

/**
 * 弹层 09:对话模式(基准 09-dialog-mode.png / 09-dialog-mode.xml)。
 *
 * 底部半屏弹层:标题「对话模式」+ 副标题「针对当前内容生效」+ 右上 X;
 * 五行单选(标题/描述/右侧选中态),文案与 dump 逐条对齐:
 * 心动模式(增强恋爱拉扯和情感张力,默认勾)/ 细腻模式(增强细节描写,
 * 情感表现细腻)/ 长对话模式(超长文本输出,沉浸内容体验)/
 * 深度思考模式(限定模式,回复等待时间长)/ 基础模式(原灵动模式,角色聪明灵动)。
 * 本地版全部免费(参考图对话风格弹层的「限免」标签属商业化元素,不引入)。
 */
@Composable
fun DialogModeSheet(
    selected: DialogMode,
    onSelect: (DialogMode) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    // 弹层底下的全屏遮罩(点击空白收起,dump:touch_outside)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF5F7189), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* 弹层本体拦截点击,不透传收起 */ }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            // 标题行:居中标题 + 右上关闭(dump:new_navigation_bar 内 X 位)
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.align(Alignment.Center)) {
                    Text(
                        "对话模式",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "针对当前内容生效",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(26.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
                )
            }
            Spacer(Modifier.height(10.dp))
            DialogMode.entries.forEach { mode ->
                ModeRow(
                    mode = mode,
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 弹层 09 单行:标题 + 描述 + 右侧选中态(选中=黄底黑勾圆,未选=灰圈)。 */
@Composable
private fun ModeRow(mode: DialogMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(mode.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(mode.desc, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(Color(0xFFF7D437), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = Color(0xFF1A1A1A),
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Box(
                Modifier
                    .size(24.dp)
                    .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
            )
        }
    }
}

/**
 * 弹层 10:对话风格(基准 10-dialog-style.png / 10-dialog-style.xml)。
 *
 * 底部半屏弹层:标题「对话风格」+ 副标题「针对当前内容生效」;
 * 「回复长度」分段三选(更短/适中/更长,选中白底黑字)+ 白色「完成」按钮。
 * 回复长度真实影响 F2 预留的连发参数([com.neethu.gouxiang.chat.BurstParams]),
 * 选择即时生效、完成收口。
 */
@Composable
fun DialogStyleSheet(
    selected: ReplyLength,
    onSelect: (ReplyLength) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF5F7189), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* 拦截透传 */ }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    "对话风格",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "针对当前内容生效",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(22.dp))
            // 回复长度分段(选中白底胶囊黑字,未选透明白字;dump:fl_slider_widget)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "回复长度",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(18.dp))
                Row(
                    Modifier
                        .weight(1f)
                        .background(Color(0xFF71839A), RoundedCornerShape(14.dp))
                        .padding(5.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
                ) {
                    ReplyLength.entries.forEach { length ->
                        Box(
                            Modifier
                                .weight(1f)
                                .background(
                                    if (length == selected) Color.White else Color.Transparent,
                                    RoundedCornerShape(11.dp),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelect(length) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                length.title,
                                color = if (length == selected) Color(0xFF1A1A1A) else Color.White,
                                fontSize = 15.sp,
                                fontWeight = if (length == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            // 完成(dump:btn_done「完成」白底全宽胶囊;选择已即时生效,完成收口)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("完成", color = Color(0xFF1A1A1A), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
