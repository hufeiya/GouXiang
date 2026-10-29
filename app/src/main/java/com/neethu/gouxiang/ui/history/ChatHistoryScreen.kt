package com.neethu.gouxiang.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.ui.ChatBubble
import com.neethu.gouxiang.ui.ChatHistoryUiState
import com.neethu.gouxiang.ui.HistoryBubbleItem

/**
 * 聊天历史页 11(基准 11-history.xml / 11-history.png):
 *
 * 与聊天页同骨架 —— 消息气泡流(user 粉泡靠右 / assistant 白泡靠左,() 动作
 * 斜体内联),外加日期分组头与组尾时间;右下互动列末位为「返回」(dump
 * message_im_view/tv_im,参考页从历史页看该钮文案即「返回」),点击回聊天页;
 * 顶栏返回键同路。只读浏览:无输入条、无发送、无状态条(历史快照不需要
 * 实时「在做什么」)。
 *
 * 消息多时分段加载(范围 3,不做真分页协议):列表顶滚到最旧一段即将露出
 * (firstVisibleItemIndex ≤ 1)即触发 [onLoadOlder],往头部拼更旧的历史段。
 */
@Composable
fun ChatHistoryScreen(
    state: ChatHistoryUiState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    val listState = rememberLazyListState()
    // reverseLayout 列表的「贴顶」= 最后几个 item(最旧一段)即将露出。
    // 留余量触发,避免贴顶瞬间白屏等待;贴底时 lastVisible 远小于 total-2,不会误触发。
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearTop, state.loadingOlder, state.endReached) {
        if (nearTop && !state.loadingOlder && !state.endReached) onLoadOlder()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101014)),
    ) {
        // ── 顶栏:返回(X)+ 标题「聊天历史」(参考页沉浸式暗底)───────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ArrowBackIosNew,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "聊天历史",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // ── 历史流(reverseLayout:最新贴底,顶滚加载更旧)────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // reverseLayout:先 emit 的在底部。组按时间正序 emit → 最新贴底;
            // 「加载更早/到底」提示描述更旧一端 → emit 在组之后(靠顶部)。
            // 所有 item 均带稳定 key:头部前拼更旧段时滚动锚点按 key 维持,不跳位。
            items(
                count = state.bubbleItems.size,
                key = { index -> state.bubbleItems[index].key },
            ) { index ->
                val item = state.bubbleItems[index]
                Column {
                    // 日期分组头(与更早一段不同日才出现;标日期切换点)
                    if (item.showDayDivider) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            item.dayLabel,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    BubbleGroupWithTime(item)
                }
            }
            if (state.loadingOlder) {
                item(key = "loading-older") {
                    HintText("加载更早的消息…", alpha = 0.5f)
                }
            }
            if (state.endReached && state.bubbleItems.isNotEmpty()) {
                item(key = "end") {
                    HintText("以上是全部历史", alpha = 0.4f)
                }
            }
            if (state.bubbleItems.isEmpty() && !state.loadingOlder) {
                item(key = "empty") {
                    HintText("暂无历史消息", alpha = 0.5f)
                }
            }
        }
        // ── 底部互动列:右下角「返回」(dump message_im_view/tv_im)───────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
            ) {
                // 11-history.png:白色「↩」图标 + 下方「返回」小字
                Text("↩", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("返回", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

/** 弱化居中小字(加载中/到底/空态)。 */
@Composable
private fun HintText(text: String, alpha: Float) {
    Text(
        text,
        color = Color.White.copy(alpha = alpha),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        textAlign = TextAlign.Center,
    )
}

/** 一组气泡 + 组尾时间(HH:mm,与气泡同侧,时间小字弱化)。 */
@Composable
private fun BubbleGroupWithTime(item: HistoryBubbleItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (item.group.fromUser) Alignment.End else Alignment.Start,
    ) {
        item.group.bubbles.forEachIndexed { index, bubble ->
            Spacer(Modifier.height(if (index == 0) 0.dp else 6.dp))
            HistorySingleBubble(bubble, fromUser = item.group.fromUser)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            item.timeLabel,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 单个气泡(与聊天页 03 同骨架:同款圆角/配色/() 动作斜体;
 * 历史快照为只读,特型(sticker/img/voice)内容一律按文本泡展示)。
 */
@Composable
private fun HistorySingleBubble(bubble: ChatBubble, fromUser: Boolean) {
    val shape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp,
    )
    val bg = if (fromUser) Color(0xFFE84D8A) else Color.White
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .background(bg, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val actionStyle = SpanStyle(
            fontStyle = FontStyle.Italic,
            color = if (fromUser) Color.White.copy(alpha = 0.75f) else Color(0xFF9A9A9A),
        )
        val bodyColor = if (fromUser) Color.White else Color(0xFF1A1A1A)
        val annotated = buildAnnotatedString {
            bubble.actions.forEach { action ->
                withStyle(actionStyle) { append("($action)") }
            }
            append(bubble.content)
        }
        Text(
            annotated,
            color = bodyColor,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        )
    }
}
