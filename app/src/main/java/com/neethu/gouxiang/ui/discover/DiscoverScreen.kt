package com.neethu.gouxiang.ui.discover

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ConversationRow
import com.neethu.gouxiang.ui.messages.formatRelativeTime

/**
 * 发现页 05(H4 本地化版):本地角色浏览 + 搜索 + 最近聊过(历史)。
 *
 * - 范围红线:公共社区/远端内容/内容审核转后续决策(K),不实现;页面上
 *   只出现本地真实数据(角色卡、真实会话时间),不出现任何静态伪造的
 *   「热度/推荐/人数」类社交数字;
 * - 「朋友圈」入口卡(H1):进入多角色合并时间线(用户帖 + 参与角色动态);
 * - 角色卡点击 → 该角色的角色主页(复用现有入口,IM 会话 id 直达)。
 */
@Composable
fun DiscoverScreen(
    characters: List<CharacterEntity>,
    conversations: List<ConversationRow>,
    chattedConversations: List<ConversationRow>,
    avatarFiles: Map<Long, String?>,
    onOpenMoments: () -> Unit = {},
    onOpenCharacterHome: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    // 搜索域(H4):名字/简介/设定,本地全量过滤(无远端)
    val filtered = if (keyword.isEmpty()) characters else characters.filter { c ->
        c.name.contains(keyword) || c.intro.contains(keyword) || c.profile.contains(keyword)
    }
    // 最近聊过(历史):按有用户消息的会话排序并去重。
    val recentIds = chattedConversations
        .sortedByDescending { it.conversation.lastAt }
        .map { it.conversation.characterId }
        .distinct()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAF7))
            .statusBarsPadding(),
    ) {
        Text(
            "发现",
            color = Color(0xFF1A1A1A),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // 朋友圈入口卡(本地多角色时间线;非社区,不出现「社区/热榜」字样)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color(0xFFF3EEFB), RoundedCornerShape(16.dp))
                .clickable { onOpenMoments() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("🌏", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("朋友圈", color = Color(0xFF1A1A1A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "你和角色们的动态时间线",
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                )
            }
            Text("进入 ›", color = Color(0xFF7A6BD6), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        // 搜索框(本地角色)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color.White, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Color(0xFFB5B5B5), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text("搜索角色名/简介/设定", color = Color(0xFFB5B5B5), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            if (keyword.isEmpty() && recentIds.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionTitle("最近聊过")
                }
                items(recentIds, key = { "recent-$it" }) { characterId ->
                    val character = characters.firstOrNull { it.id == characterId }
                    if (character != null) {
                        val lastAt = chattedConversations
                            .filter { it.conversation.characterId == characterId }
                            .maxOf { it.conversation.lastAt }
                        CharacterRow(character, avatarFiles[characterId], lastAt, onOpenCharacterHome, conversations)
                    }
                }
            }
            item(key = "all-title") {
                SectionTitle(if (keyword.isEmpty()) "全部角色" else "搜索结果")
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有匹配「$keyword」的角色",
                        color = Color(0xFF9A9A9A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(filtered, key = { "all-${it.id}" }) { character ->
                // 搜索结果行不重复展示「最近聊过」区内已出现的角色
                if (keyword.isEmpty() || character.id !in recentIds) {
                    CharacterRow(
                        character,
                        avatarFiles[character.id],
                        null,
                        onOpenCharacterHome,
                        conversations,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = Color(0xFF9A9A9A),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 角色行:头像 + 名字 + 简介(真实数据;[lastAt] 非空时展示真实最近时间)。 */
@Composable
private fun CharacterRow(
    character: CharacterEntity,
    avatarFile: String?,
    lastAt: Long?,
    onOpenCharacterHome: (Long) -> Unit,
    conversations: List<ConversationRow>,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // 角色主页入口以会话为锚(与聊天页/首页同一条路径):优先 IM 会话,
                // 兜底任一会话;无会话不跳(C2 闭环保证建卡即有会话)
                val convId = conversations
                    .firstOrNull { it.conversation.characterId == character.id && it.conversation.mode == "im" }
                    ?.conversation?.id
                    ?: conversations
                        .firstOrNull { it.conversation.characterId == character.id }
                        ?.conversation?.id
                if (convId != null) onOpenCharacterHome(convId)
            }
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .background(Color(0xFFF1F1EC), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarFile != null) {
                AsyncImage(
                    model = java.io.File(avatarFile),
                    contentDescription = character.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF1F1EC), CircleShape),
                )
            } else {
                Text(character.name.take(1), color = Color(0xFF9A9A9A), fontSize = 18.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(character.name, color = Color(0xFF1A1A1A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                character.intro.ifBlank { character.profile },
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        lastAt?.let {
            Text(
                formatRelativeTime(it),
                color = Color(0xFFB5B5B5),
                fontSize = 12.sp,
            )
        }
    }
}
