package com.neethu.gouxiang.ui.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.ConversationRow

/**
 * 消息·聊过列表(基准 02-messages.png):
 * 顶部「赞过/聊过」tab;互动消息(品红铃铛)/系统通知(黄)固定行 +
 * 会话行(头像/名字/摘要/时间);手机聊天会话头像右下有黄色小手机角标。
 */
@Composable
fun MessagesScreen(
    rows: List<ConversationRow>,
    avatarFiles: Map<Long, String?>,
    onOpenChat: (Long) -> Unit = {},
    /** 手机聊天形态会话行(mode=phone)的入口(F4)。 */
    onOpenPhoneChat: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        TopBar()
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            // 固定行 1:互动消息(品红铃铛)
            item {
                FixedRow(
                    title = "互动消息",
                    summary = "点击查看互动消息",
                    time = "",
                    avatar = {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(Color(0xFFE84D8A), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                )
            }
            // 固定行 2:系统通知(黄)
            item {
                FixedRow(
                    title = "系统通知",
                    summary = "「心动指令」新用户9折券限时领",
                    time = "2天前",
                    avatar = {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(Color(0xFFF7E733), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🚕", fontSize = 24.sp)
                        }
                    },
                )
            }
            // 会话行(按 lastAt 倒序,数据层已排好);mode=phone 的会话进
            // 手机聊天页(F4 范围 1 的入口),其余进陪伴聊天页
            items(rows, key = { it.conversation.id }) { row ->
                ConversationItem(row, avatarFiles[row.conversation.characterId]) {
                    if (row.isPhoneChat) onOpenPhoneChat(row.conversation.id)
                    else onOpenChat(row.conversation.id)
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

/** 顶部:左相机圆环 + 「赞过 | 聊过」 + 右搜索。 */
@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 快门圆环(占位)
        Box(
            Modifier
                .size(26.dp)
                .background(Color(0xFF2A2A2A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .background(
                        Brush.sweepGradient(listOf(Color(0xFF2A2A2A), Color.White, Color(0xFF2A2A2A))),
                        CircleShape,
                    ),
            )
            Box(
                Modifier
                    .size(10.dp)
                    .background(Color(0xFF2A2A2A), CircleShape),
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(
                "赞过",
                color = Color(0xFFB9B9B9),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "聊过",
                    color = Color(0xFF1A1A1A),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp)),
                )
            }
        }
        Icon(
            Icons.Outlined.Search,
            contentDescription = "搜索",
            tint = Color(0xFF1A1A1A),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 固定行(互动消息/系统通知)。 */
@Composable
private fun FixedRow(
    title: String,
    summary: String,
    time: String,
    avatar: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* 占位:互动消息页后续功能 */ }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFF1A1A1A), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(summary, color = Color(0xFF9A9A9A), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (time.isNotEmpty()) {
            Text(time, color = Color(0xFFB9B9B9), fontSize = 13.sp)
        }
    }
}

/** 会话行:头像(+手机角标)/名字/摘要/时间;点击进入聊天主页 03。 */
@Composable
private fun ConversationItem(row: ConversationRow, avatarFile: String?, onClick: () -> Unit) {
    val char = row.character ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (avatarFile != null) {
                AsyncImage(
                    model = java.io.File(avatarFile),
                    contentDescription = char.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0xFFEDEDED), CircleShape),
                )
            } else {
                // 占位头像:按角色哈希渐变 + 首字
                Box(
                    Modifier
                        .size(56.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFB9C6D8), Color(0xFFD9CBE0))),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        char.name.take(1),
                        color = Color(0xFF5A5A66),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (row.isPhoneChat) {
                // 黄色小手机角标(右下)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(Color(0xFFF7E733), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📱", fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(char.name, color = Color(0xFF1A1A1A), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                row.conversation.lastSummary,
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 未读角标(D 工作包 D1/D4:主动消息未读的应用内查看路径——通知权限
        // 关闭时消息仍落库,红点计数在消息列表可见,进会话即清零)
        if (row.conversation.unread > 0) {
            Box(
                Modifier
                    .background(Color(0xFFE84D8A), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.conversation.unread.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            formatRelativeTime(row.conversation.lastAt),
            color = Color(0xFFB9B9B9),
            fontSize = 13.sp,
        )
    }
}

/** 相对时间:「刚刚 / N分钟前 / N小时前 / 2天前 / 3天前」。 */
internal fun formatRelativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - at
    val minutes = diff / 60_000
    return when {
        diff < 60_000 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        minutes < 60 * 24 * 7 -> "${minutes / 60 / 24}天前"
        else -> "${minutes / 60 / 24 / 7}周前"
    }
}
