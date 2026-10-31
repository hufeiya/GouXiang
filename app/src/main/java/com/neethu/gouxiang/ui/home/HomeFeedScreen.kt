package com.neethu.gouxiang.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.neethu.gouxiang.data.HomeFeedCard

/**
 * 首页·推荐流(基准 01-home-feed.png):
 * 沉浸式 AI 剧卡(全屏图 + 介绍条 + 开场白气泡 + 名字> + 点赞/分享/评论/历史),
 * 顶部「AI 剧/推荐」双 tab。上下滑动切换人物卡;左右滑动预留为切换 AI 剧
 * (AI 剧未开放,tab 置灰,横滑无行为)。
 *
 * F3 接线(P1):「名字 >」= openCharacterHome、互动列「历史」= openHistory
 * (与 im 聊天页同款入口,审计报告 §4-3/§5.2);开场白气泡点击进聊天。
 * 有意裁剪(报告 §5.3,无付费/无社交后端红线):点赞/分享/评论/⚡ 为静态
 * 装饰不接行为;硬编码互动数列(361/5/28)整列移除,避免「看起来坏」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedScreen(
    cards: List<HomeFeedCard>,
    avatarFiles: Map<Long, String?>,
    introBarText: String,
    /** F3:点「名字 >」进角色主页(回调带对应会话 id;无会话=-1 不响应)。 */
    onOpenCharacterHome: (Long) -> Unit = {},
    /** F3:点互动列「历史」进聊天历史页(回调带对应会话 id;无会话=-1 不响应)。 */
    onOpenHistory: (Long) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF151515)))
        } else {
            val pagerState = rememberPagerState(pageCount = { cards.size })
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                FeedCard(
                    card = cards[page],
                    avatarFile = avatarFiles[cards[page].character.id],
                    introBarText = introBarText,
                    onOpenCharacterHome = onOpenCharacterHome,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
        // 顶部 tab 行(沉浸式,叠在图上)
        TopTabsRow(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )
    }
}

/** 顶部:黄 V 盾 + 「AI 剧 | 推荐」双 tab + 搜索 + 喇叭。 */
@Composable
private fun TopTabsRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 装饰图标(黄 V 盾形,占位)
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "装饰",
            tint = Color(0xFFFFC94D),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AI 剧未开放:置灰禁用(横滑预留,不做任何行为)
            Text(
                "AI 剧",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
            )
            Spacer(Modifier.width(18.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "推荐",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                // 选中 tab 下的白色短横线
                Box(
                    Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            }
        }
        Icon(
            Icons.Outlined.Search,
            contentDescription = "搜索",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Icon(
            Icons.Outlined.VolumeUp,
            contentDescription = "音量",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 单张剧卡:全屏头像 + 底部信息叠层。 */
@Composable
private fun FeedCard(
    card: HomeFeedCard,
    avatarFile: String?,
    introBarText: String,
    onOpenCharacterHome: (Long) -> Unit = {},
    onOpenHistory: (Long) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2A2A2E)),
    ) {
        // 全屏角色形象(生成缓存命中显示真图,未生成显示渐变占位)
        if (avatarFile != null) {
            AsyncImage(
                model = java.io.File(avatarFile),
                contentDescription = card.character.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF565866), Color(0xFF2C2C34), Color(0xFF1A1A20)),
                        ),
                    ),
            )
        }
        // 左侧竖排「由 AI 生成」
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
        ) {
            "由 AI 生成".forEach { ch ->
                Text(
                    ch.toString(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
        // 底部叠层
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                )
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(220.dp))
            // 介绍条(黑胶囊)
            Box(
                Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📄 ", fontSize = 13.sp)
                    Text(
                        "介绍: $introBarText",
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 开场白气泡(白) + ⚡ 圆钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .weight(1f, fill = false),
                ) {
                    Text(
                        card.openingLine,
                        color = Color(0xFF1A1A1A),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Color(0xFF3A3A3A).copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚡", fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // 名字行 + 互动列。F3:「名字 >」接 openCharacterHome(与 im 聊天页
            // 同款入口);点赞/分享/评论为有意裁剪的静态装饰(报告 §5.3),
            // 硬编码数字列移除 —— 「历史」保留为活入口(聊天页同款接线)。
            Row(verticalAlignment = Alignment.Bottom) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (card.conversationId > 0) onOpenCharacterHome(card.conversationId)
                        },
                ) {
                    Text(
                        card.character.name + " >",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "@${card.character.source}、" + card.character.intro.take(18) + "…",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ActionItem(
                    Icons.Filled.History,
                    "历史",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (card.conversationId > 0) onOpenHistory(card.conversationId)
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** 右侧互动列:图标 + 数字(历史无数字)。 */
@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(start = 18.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
