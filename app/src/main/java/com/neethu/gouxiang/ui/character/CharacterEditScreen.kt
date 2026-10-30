package com.neethu.gouxiang.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import java.io.File

/**
 * 角色创建/编辑表单页(C 工作包 C1):
 *
 * - 七个字段:角色名/角色设定/角色简介/台词示例/开场白/形象描述/绘图风格;
 *   必填(名称/设定/开场白)与长度校验错误逐字段显示,空白与重复名称提示明确;
 * - 新建路径自动保存本地草稿(重进恢复 + 顶部一次性提示);
 * - 「预览」切换卡片预览态(形象/开场白按聊天页语感呈现);
 * - 保存:校验 → 原子落库(角色 + 首个 IM 会话 + 开场白,手机会话一并补建)
 *   → 直接进入续聊;编辑模式按「保存修改」提交且不动会话历史;
 * - 形象区:头像生成状态(生成中/失败可重试)+ 本地图片管理
 *   (设为当前/删除,C3);新建未保存时提示保存后自动生成。
 */
@Composable
fun CharacterEditScreen(
    ui: CharacterEditorUi,
    gallery: List<File>,
    currentAvatarFile: String?,
    avatarState: AvatarGenState?,
    onField: (CharacterField, String) -> Unit,
    onTogglePreview: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onRetryAvatar: (Long) -> Unit,
    onSetAvatarCurrent: (Long, String) -> Unit,
    onDeleteAvatar: (Long, String) -> Unit,
    onRefreshGallery: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14141A))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶栏:返回 + 标题 + 预览切换
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (ui.isEditing) CharacterFormCopy.EDITOR_TITLE_EDIT else CharacterFormCopy.EDITOR_TITLE_NEW,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTogglePreview(!ui.showPreview) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (ui.showPreview) CharacterFormCopy.BUTTON_EDIT_BACK else CharacterFormCopy.BUTTON_PREVIEW,
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
        if (ui.showPreview) {
            EditorPreview(ui = ui, currentAvatarFile = currentAvatarFile)
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            // 草稿恢复提示(一次性)
            if (ui.draftRestored) {
                NoticeBar(CharacterFormCopy.DRAFT_RESTORED, tint = Color(0xFF7BD88F))
                Spacer(Modifier.height(8.dp))
            }
            // 整页错误(重名/事务失败)
            ui.errorMessage?.let {
                NoticeBar(it, tint = Color(0xFFFF7B72))
                Spacer(Modifier.height(8.dp))
            }
            FormField(
                label = CharacterFormCopy.LABEL_NAME,
                value = ui.name,
                onValue = { onField(CharacterField.NAME, it) },
                hint = "请输入角色名称",
                error = ui.errors.name,
                maxLength = CharacterFormLimits.NAME_MAX,
            )
            FormField(
                label = CharacterFormCopy.LABEL_PROFILE,
                value = ui.profile,
                onValue = { onField(CharacterField.PROFILE, it) },
                hint = CharacterFormCopy.HINT_PROFILE,
                error = ui.errors.profile,
                maxLength = CharacterFormLimits.PROFILE_MAX,
                minLines = 3,
            )
            FormField(
                label = CharacterFormCopy.LABEL_INTRO,
                value = ui.intro,
                onValue = { onField(CharacterField.INTRO, it) },
                hint = CharacterFormCopy.HINT_INTRO,
                error = ui.errors.intro,
                maxLength = CharacterFormLimits.INTRO_MAX,
                minLines = 2,
            )
            FormField(
                label = CharacterFormCopy.LABEL_LINES,
                value = ui.exampleLines,
                onValue = { onField(CharacterField.LINES, it) },
                hint = CharacterFormCopy.HINT_LINES,
                error = ui.errors.exampleLines,
                maxLength = CharacterFormLimits.LINES_MAX,
                minLines = 2,
            )
            FormField(
                label = CharacterFormCopy.LABEL_OPENING,
                value = ui.openingLine,
                onValue = { onField(CharacterField.OPENING, it) },
                hint = CharacterFormCopy.HINT_OPENING,
                error = ui.errors.openingLine,
                maxLength = CharacterFormLimits.OPENING_MAX,
                minLines = 2,
            )
            // ── 形象与风格(喂生图;C3 版本化重生成)──────────────────
            AvatarSection(
                ui = ui,
                currentAvatarFile = currentAvatarFile,
                avatarState = avatarState,
                onRetryAvatar = onRetryAvatar,
            )
            FormField(
                label = CharacterFormCopy.LABEL_APPEARANCE,
                value = ui.appearance,
                onValue = { onField(CharacterField.APPEARANCE, it) },
                hint = CharacterFormCopy.HINT_APPEARANCE,
                error = ui.errors.appearance,
                maxLength = CharacterFormLimits.APPEARANCE_MAX,
                minLines = 2,
            )
            FormField(
                label = CharacterFormCopy.LABEL_STYLE,
                value = ui.appearanceStyle,
                onValue = { onField(CharacterField.STYLE, it) },
                hint = CharacterFormCopy.HINT_STYLE,
                error = ui.errors.appearanceStyle,
                maxLength = CharacterFormLimits.STYLE_MAX,
            )
            // 风格词建议 chips(可自由输入,点选即填)
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CharacterFormCopy.STYLE_PRESETS.size) { index ->
                    val preset = CharacterFormCopy.STYLE_PRESETS[index]
                    Box(
                        Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onField(CharacterField.STYLE, preset) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(preset, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
            // ── 本地图片管理(C3)────────────────────────────────────
            if (ui.isEditing) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        CharacterFormCopy.GALLERY_TITLE,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "刷新",
                        color = Color(0xFF9FB4FF),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRefreshGallery(ui.editingCharacterId) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (gallery.isEmpty()) {
                    Text(
                        CharacterFormCopy.GALLERY_EMPTY,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().height(((gallery.size / 3 + 1) * 150).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(gallery, key = { it.absolutePath }) { file ->
                            GalleryCell(
                                file = file,
                                isCurrent = currentAvatarFile == file.absolutePath,
                                onSetCurrent = { onSetAvatarCurrent(ui.editingCharacterId, file.name) },
                                onDelete = { onDeleteAvatar(ui.editingCharacterId, file.name) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            // 保存按钮
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (ui.saving) Color(0xFF7C5CFF).copy(alpha = 0.5f) else Color(0xFF7C5CFF),
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !ui.saving,
                    ) { onSave() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        ui.saving -> CharacterFormCopy.SAVING
                        ui.isEditing -> CharacterFormCopy.BUTTON_SAVE
                        else -> CharacterFormCopy.BUTTON_SAVE_AND_CHAT
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 形象区:当前头像 + 生成状态/重试 + 形象改写提示。 */
@Composable
private fun AvatarSection(
    ui: CharacterEditorUi,
    currentAvatarFile: String?,
    avatarState: AvatarGenState?,
    onRetryAvatar: (Long) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (currentAvatarFile != null) {
                AsyncImage(
                    model = File(currentAvatarFile),
                    contentDescription = "当前形象",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF565866), Color(0xFF2C2C34))), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.name.take(1).ifBlank { "?" }, color = Color.White, fontSize = 22.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when {
                avatarState is AvatarGenState.Generating -> Text(
                    CharacterFormCopy.AVATAR_GENERATING,
                    color = Color(0xFFF2C94C),
                    fontSize = 13.sp,
                )
                avatarState is AvatarGenState.Failed -> {
                    Text(
                        avatarState.reason,
                        color = Color(0xFFFF7B72),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        CharacterFormCopy.AVATAR_RETRY,
                        color = Color(0xFF9FB4FF),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRetryAvatar(ui.editingCharacterId) },
                    )
                }
                !ui.isEditing -> Text(
                    CharacterFormCopy.AVATAR_PENDING,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
                else -> Text(
                    CharacterFormCopy.LABEL_APPEARANCE,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** 单个表单字段:标签 + 输入框 + 逐字段错误 + 字数提示。 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    error: String?,
    maxLength: Int,
    minLines: Int = 1,
) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            "${value.length}/$maxLength",
            color = if (value.length > maxLength) Color(0xFFFF7B72) else Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(hint, color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        minLines = minLines,
        isError = error != null,
        supportingText = { if (error != null) Text(error, color = Color(0xFFFF7B72), fontSize = 12.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF7C5CFF),
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            cursorColor = Color(0xFF7C5CFF),
            focusedContainerColor = Color(0xFF1E1E26),
            unfocusedContainerColor = Color(0xFF1E1E26),
        ),
    )
}

/** 提示横条(草稿恢复/整页错误)。 */
@Composable
private fun NoticeBar(text: String, tint: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text, color = tint, fontSize = 13.sp)
    }
}

/** 图库单元:缩略图 + 当前标记 + 设为当前/删除。 */
@Composable
private fun GalleryCell(file: File, isCurrent: Boolean, onSetCurrent: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.background(Color(0xFF1E1E26), RoundedCornerShape(12.dp)).padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = file,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(Color(0xFF7C5CFF).copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(CharacterFormCopy.GALLERY_CURRENT, color = Color.White, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                CharacterFormCopy.GALLERY_SET_CURRENT,
                color = if (isCurrent) Color.White.copy(alpha = 0.4f) else Color(0xFF9FB4FF),
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isCurrent,
                    ) { onSetCurrent() },
            )
            Text(
                CharacterFormCopy.GALLERY_DELETE,
                color = Color(0xFFFF7B72),
                fontSize = 12.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDelete() },
            )
        }
    }
}

/** 预览态:按聊天页语感呈现形象/名字/简介/开场白。 */
@Composable
private fun EditorPreview(ui: CharacterEditorUi, currentAvatarFile: String?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (currentAvatarFile != null) {
                AsyncImage(
                    model = File(currentAvatarFile),
                    contentDescription = ui.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF565866), Color(0xFF2C2C34)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.name.take(1).ifBlank { "?" }, color = Color.White, fontSize = 46.sp)
                }
            }
            Text(
                ui.name,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        if (ui.intro.isNotBlank()) {
            Text(ui.intro, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(12.dp))
        }
        if (ui.openingLine.isNotBlank()) {
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(ui.openingLine, color = Color(0xFF1A1A1A), fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${CharacterFormCopy.LABEL_PROFILE}:${ui.profile.ifBlank { "(未填)" }}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${CharacterFormCopy.LABEL_APPEARANCE}:${ui.appearance.ifBlank { "(未填)" }}" +
                (if (ui.appearanceStyle.isNotBlank()) " / ${ui.appearanceStyle}" else ""),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}
