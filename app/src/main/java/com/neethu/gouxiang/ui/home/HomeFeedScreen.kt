package com.neethu.gouxiang.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.neethu.gouxiang.data.HomeFeedCard
import com.neethu.gouxiang.data.CatalogCharacter
import com.neethu.gouxiang.ui.chat.InputBar
import com.neethu.gouxiang.ui.chat.NameAndActionsRow
import com.neethu.gouxiang.ui.chat.HomeChatPresentation
import androidx.compose.foundation.shape.CircleShape

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
    catalog: List<CatalogCharacter> = emptyList(),
    onOpenHall: () -> Unit = {},
    onOpenCatalogCharacter: (CatalogCharacter) -> Unit = {},
    onStartCatalogChat: (CatalogCharacter) -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    /** F3:点「名字 >」进角色主页(回调带对应会话 id;无会话=-1 不响应)。 */
    onOpenCharacterHome: (Long) -> Unit = {},
    /** F3:点互动列「历史」进聊天历史页(回调带对应会话 id;无会话=-1 不响应)。 */
    onOpenHistory: (Long) -> Unit = {},
    onActiveItemChanged: (FeedItem?) -> Unit = {},
    openingFinished: Boolean = false,
    soundEnabled: Boolean = true,
    onToggleSound: () -> Unit = {},
    embeddedKey: String? = null,
    canSwipe: Boolean = true,
    chatContent: @Composable (FeedItem, HomeChatPresentation, (() -> Unit)?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val activeChanged by rememberUpdatedState(onActiveItemChanged)
    DisposableEffect(Unit) {
        onDispose {
            activeChanged(null)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // 同一轮浏览保持顺序，已有会话的人物保留历史入口并去除目录重复项。
        val shuffleSeed = remember { kotlin.random.Random.nextLong() }
        // 固定同一轮浏览中首次看到的立绘；目录人物转存时 cover 不替换 portrait。
        val imageModels = remember { mutableMapOf<String, Any>() }
        val items = remember(cards, catalog, shuffleSeed) {
            val localSources = cards.map { it.character.source }.toSet()
            (cards.map { FeedItem.Local(it.copy(openingLine = it.character.openingLine)) } +
                catalog.filterNot { "maoxiang:${it.sourceId}" in localSources }.map { FeedItem.Catalog(it) })
                .sortedBy { item ->
                    val key = item.stableKey()
                    kotlin.random.Random(shuffleSeed xor key.hashCode().toLong()).nextLong()
                }
        }
        if (items.isEmpty()) {
            LaunchedEffect(Unit) { activeChanged(null) }
            Box(Modifier.fillMaxSize().background(Color(0xFF151515)))
            TopTabsRow(
                onOpenHall = onOpenHall, soundEnabled = soundEnabled, onToggleSound = onToggleSound,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        } else {
            val pagerState = rememberPagerState(pageCount = { items.size })
            val pagerScope = rememberCoroutineScope()
            val swipeAllowed by rememberUpdatedState(canSwipe)
            val currentItems by rememberUpdatedState(items)
            val nextCharacter: (() -> Unit)? = if (items.size > 1) {
                {
                    if (swipeAllowed) pagerScope.launch {
                        val count = currentItems.size
                        if (swipeAllowed && count > 1) {
                            pagerState.animateScrollToPage((pagerState.settledPage + 1) % count)
                        }
                    }
                }
            } else null
            LaunchedEffect(pagerState) {
                var lastKey: String? = null
                snapshotFlow { currentItems.getOrNull(pagerState.settledPage) }.collect { item ->
                    val key = item?.stableKey()
                    if (key != lastKey) {
                        activeChanged(item)
                        lastKey = key
                    }
                }
            }
            VerticalPager(
                state = pagerState,
                key = { items[it].stableKey() },
                userScrollEnabled = canSwipe,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                val candidateModel: Any? = when (item) {
                    is FeedItem.Catalog -> "file:///android_asset/${item.character.portraitAsset}"
                    is FeedItem.Local -> catalog.firstOrNull { "maoxiang:${it.sourceId}" == item.card.character.source }
                        ?.let { "file:///android_asset/${it.portraitAsset}" }
                        ?: avatarFiles[item.card.character.id]?.let { java.io.File(it) }
                        ?: item.card.character.avatarFile.takeIf { it.isNotBlank() }?.let {
                            java.io.File(java.io.File(context.filesDir, "avatars"), it).takeIf { file -> file.isFile }
                        }
                }
                val imageModel = imageModels[item.stableKey()] ?: candidateModel?.also {
                    imageModels[item.stableKey()] = it
                }
                if (page == pagerState.settledPage) {
                    Box(Modifier.fillMaxSize()) {
                        val presentation = when (item) {
                            is FeedItem.Local -> HomeChatPresentation(
                                name = item.card.character.name,
                                source = catalog.firstOrNull { "maoxiang:${it.sourceId}" == item.card.character.source }
                                    ?.creatorName ?: item.card.character.source,
                                intro = item.card.character.intro.ifBlank { introBarText },
                                openingLine = item.card.openingLine,
                                imageModel = imageModel,
                                openingFinished = openingFinished,
                            )
                            is FeedItem.Catalog -> HomeChatPresentation(
                                name = item.character.name,
                                source = item.character.creatorName,
                                intro = item.character.intro.ifBlank { introBarText },
                                openingLine = item.character.openingLine,
                                imageModel = imageModel,
                                openingFinished = openingFinished,
                            )
                        }
                        chatContent(item, presentation, nextCharacter)

                    }
                } else when (item) {
                    is FeedItem.Local -> FeedCard(
                        name = item.card.character.name,
                        intro = item.card.character.intro,
                        source = catalog.firstOrNull { "maoxiang:${it.sourceId}" == item.card.character.source }
                            ?.creatorName ?: item.card.character.source,
                        openingLine = item.card.openingLine,
                        imageModel = imageModel,
                        introBarText = item.card.character.intro.ifBlank { introBarText },
                        openingFinished = openingFinished && pagerState.settledPage == page,
                        onOpen = { if (item.card.conversationId > 0) onOpenCharacterHome(item.card.conversationId) },
                        onChat = if (item.card.conversationId > 0) ({ onOpenChat(item.card.conversationId) }) else null,
                        onHistory = if (item.card.conversationId > 0) ({ onOpenHistory(item.card.conversationId) }) else null,
                    )
                    is FeedItem.Catalog -> FeedCard(
                        name = item.character.name,
                        intro = item.character.intro,
                        source = item.character.creatorName,
                        openingLine = item.character.openingLine,
                        imageModel = imageModel,
                        introBarText = item.character.intro.ifBlank { introBarText },
                        openingFinished = openingFinished && pagerState.settledPage == page,
                        onOpen = { onOpenCatalogCharacter(item.character) },
                        onChat = { onStartCatalogChat(item.character) },
                    )
                }
            }
            TopTabsRow(
                onOpenHall = onOpenHall,
                soundEnabled = soundEnabled,
                onToggleSound = onToggleSound,
                onNextCharacter = nextCharacter,
                nextCharacterEnabled = canSwipe,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        }

    }
}

/** 顶部:黄 V 盾 + 「AI 剧 | 推荐」双 tab + 搜索 + 喇叭。 */
@Composable
private fun TopTabsRow(
    onOpenHall: () -> Unit,
    soundEnabled: Boolean,
    onToggleSound: () -> Unit,
    onNextCharacter: (() -> Unit)? = null,
    nextCharacterEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
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
        if (onNextCharacter != null) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .semantics { contentDescription = "换人物" }
                    .clickable(enabled = nextCharacterEnabled, onClick = onNextCharacter)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("换人", color = Color.White.copy(alpha = if (nextCharacterEnabled) 1f else 0.45f), fontSize = 13.sp)
            }
        }
        IconButton(onClick = onOpenHall, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Search, contentDescription = "人物大厅", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onToggleSound, modifier = Modifier.size(40.dp)) {
            Icon(
                if (soundEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                contentDescription = if (soundEnabled) "关闭声音" else "开启声音",
                tint = Color.White,
            )
        }
    }
}

sealed interface FeedItem {
    data class Local(val card: HomeFeedCard) : FeedItem
    data class Catalog(val character: CatalogCharacter) : FeedItem
}

/** 目录人物转为本地人物后仍使用同一个键，避免卡片重排。 */
fun FeedItem.stableKey(): String = when (this) {
    is FeedItem.Local -> card.character.source.takeIf { it.startsWith("maoxiang:") }
        ?: "local:${card.character.id}"
    is FeedItem.Catalog -> "maoxiang:${character.sourceId}"
}

/** 单张剧卡:全屏头像 + 底部信息叠层。 */
@Composable
private fun FeedCard(
    name: String,
    intro: String,
    source: String,
    openingLine: String,
    imageModel: Any?,
    introBarText: String,
    openingFinished: Boolean,
    onOpen: () -> Unit,
    onChat: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2A2A2E)),
    ) {
        // 全屏角色形象(生成缓存命中显示真图,未生成显示渐变占位)
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = name,
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
        // 与嵌入聊天页共用相同的底部区域和输入控件。开场白朗读期间只展示控件，
        // 会话就绪后才接收输入；这样切换时人物和操作区不会整体跳动。
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            if (openingFinished) {
                Text(
                    "介绍: $introBarText",
                    modifier = Modifier.padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
            }
            Box(
                Modifier.padding(horizontal = 12.dp)
                    .then(if (openingFinished) Modifier.background(Color.White, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)) else Modifier)
                    .then(if (onChat != null && openingFinished) Modifier.clickable { onChat() } else Modifier)
                    .heightIn(max = 156.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    openingLine,
                    color = if (openingFinished) Color(0xFF1A1A1A) else Color.White,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier.size(34.dp).background(Color(0xFF3A3A3A).copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("⚡", fontSize = 15.sp) }
            }
            Spacer(Modifier.height(18.dp))
            NameAndActionsRow(name = name, source = source, onNameClick = onOpen, onOpenHistory = onHistory, showDecorativeActions = false)
            Spacer(Modifier.height(12.dp))
            InputBar(sending = false, onSend = {}, embeddedInHome = true, enabled = false)
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
