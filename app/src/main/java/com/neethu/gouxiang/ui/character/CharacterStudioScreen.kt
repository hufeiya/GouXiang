package com.neethu.gouxiang.ui.character

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ConversationRow

/**
 * 创作 AI+(C 工作包入口页,原占位 tab 落地):
 *
 * - 顶部「+ 新建角色」→ 编辑表单页(C1 手动创建,支持本地草稿);
 * - 角色列表(种子 + 自建同列):头像/名字/来源 + 头像生成状态行
 *   (生成中/失败可点重试,C3)+ 行内操作:聊天(IM 会话)/手机聊天
 *   (phone 会话,存在才显示)/编辑(C2:从列表可打开续聊与编辑)。
 *
 * 数据口径:角色行来自 characters 表;会话行来自 conversations 表,行内按钮
 * 按会话存在性显示 —— 角色与会话以 ID 关联,新增角色不重置种子或既有历史。
 */
@Composable
fun CharacterStudioScreen(
    characters: List<CharacterEntity>,
    conversations: List<ConversationRow>,
    avatarFiles: Map<Long, String?>,
    avatarStates: Map<Long, AvatarGenState>,
    onCreateNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenPhoneChat: (Long) -> Unit,
    onRetryAvatar: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14141A))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶栏
        Text(
            CharacterFormCopy.STUDIO_TITLE,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        // 新建入口
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF7C5CFF), RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCreateNew() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    CharacterFormCopy.STUDIO_NEW_BUTTON,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (characters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    CharacterFormCopy.STUDIO_EMPTY_HINT,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 18.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(characters, key = { it.id }) { character ->
                StudioRow(
                    character = character,
                    avatarFile = avatarFiles[character.id],
                    avatarState = avatarStates[character.id],
                    imConversationId = conversations.firstOrNull {
                        it.conversation.characterId == character.id && it.conversation.mode == "im"
                    }?.conversation?.id,
                    phoneConversationId = conversations.firstOrNull {
                        it.conversation.characterId == character.id && it.conversation.mode == "phone"
                    }?.conversation?.id,
                    onEdit = onEdit,
                    onOpenChat = onOpenChat,
                    onOpenPhoneChat = onOpenPhoneChat,
                    onRetryAvatar = onRetryAvatar,
                )
            }
        }
    }
}

/** 创作页单行:头像 + 名字/来源/头像状态 + 操作按钮组。 */
@Composable
private fun StudioRow(
    character: CharacterEntity,
    avatarFile: String?,
    avatarState: AvatarGenState?,
    imConversationId: Long?,
    phoneConversationId: Long?,
    onEdit: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenPhoneChat: (Long) -> Unit,
    onRetryAvatar: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E26), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像(生成缓存命中显示真图,否则渐变占位 + 名字首字)
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (avatarFile != null) {
                AsyncImage(
                    model = java.io.File(avatarFile),
                    contentDescription = character.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF565866), Color(0xFF2C2C34)),
                            ),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(character.name.take(1), color = Color.White, fontSize = 20.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                character.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                character.source,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
            // 头像生成状态行(C3:生成中/失败可见;失败整行可点重试)
            when (avatarState) {
                is AvatarGenState.Generating -> {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        CharacterFormCopy.AVATAR_GENERATING,
                        color = Color(0xFFF2C94C),
                        fontSize = 12.sp,
                    )
                }
                is AvatarGenState.Failed -> {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${avatarState.reason} · ${CharacterFormCopy.AVATAR_RETRY}",
                        color = Color(0xFFFF7B72),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRetryAvatar(character.id) },
                    )
                }
                else -> Unit
            }
        }
        Spacer(Modifier.width(8.dp))
        // 行内操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (imConversationId != null) {
                StudioChip(CharacterFormCopy.ROW_CHAT, filled = true) {
                    onOpenChat(imConversationId)
                }
                Spacer(Modifier.width(6.dp))
            }
            if (phoneConversationId != null) {
                StudioChip(CharacterFormCopy.ROW_PHONE) {
                    onOpenPhoneChat(phoneConversationId)
                }
                Spacer(Modifier.width(6.dp))
            }
            StudioChip(CharacterFormCopy.ROW_EDIT) { onEdit(character.id) }
        }
    }
}

/** 行内小按钮(聊天/手机聊天/编辑)。 */
@Composable
private fun StudioChip(label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (filled) Color(0xFF7C5CFF) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(999.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
