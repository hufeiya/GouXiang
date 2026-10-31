package com.neethu.gouxiang.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 性别(单选)。id 即 UserTagPrefs 持久化值。 */
enum class UserGender(val id: String, val label: String, val emoji: String) {
    MALE("male", "男生", "🙋‍♂️"),
    FEMALE("female", "女生", "🙋‍♀️"),
    ;

    companion object {
        fun fromId(id: String?): UserGender? = entries.firstOrNull { it.id == id }
    }
}

/** 喜好标签(多选)。 */
data class UserTag(val id: String, val label: String, val emoji: String)

/**
 * 标签目录(2026-10-31 用户标签功能,口径对齐参考图「选择标签,让 Ta 更懂你」):
 * 性别 2 项 + 喜好 17 项,「至少选4个」= [MIN_SELECTED_TAGS]。
 */
object UserTagCatalog {

    /** 喜好最少选择数(参考图「至少选4个」;确认按钮门控)。 */
    const val MIN_SELECTED_TAGS = 4

    val TAGS: List<UserTag> = listOf(
        UserTag("campus", "校园", "🏫"),
        UserTag("urban", "都市", "🏢"),
        UserTag("ancient", "古代", "🏰"),
        UserTag("anime", "二次元", "・ω・"),
        UserTag("career", "职场", "💼"),
        UserTag("fantasy", "玄幻", "🐉"),
        UserTag("knowledge", "有用知识", "🧠"),
        UserTag("companion", "情感陪伴", "🫂"),
        UserTag("fairytale", "奇幻", "🧚"),
        UserTag("anthropomorphic", "拟人", "😺"),
        UserTag("suspense", "悬疑惊悚", "😱"),
        UserTag("fun", "趣味玩法", "⛳"),
        UserTag("simulator", "模拟器", "🕹️"),
        UserTag("script", "剧本杀&密室", "🎬"),
        UserTag("scifi", "科幻", "👽"),
        UserTag("turtle_soup", "海龟汤", "🐢"),
        UserTag("romance", "言情", "❤️"),
    )

    fun byId(id: String): UserTag? = TAGS.firstOrNull { it.id == id }
}

private val SheetYellow = Color(0xFFFFE100)
private val InkDark = Color(0xFF111111)
private val InkGray = Color(0xFF999999)
private val ChipBorder = Color(0xFFECECEC)

/**
 * 用户标签弹层(2026-10-31,参考图等款半屏底部弹层):
 *
 * - 首次进入主框架未完成选择时自动弹出;我的页「个人标签」入口复用同一弹层;
 * - 性别单选 + 喜好多选(至少 [UserTagCatalog.MIN_SELECTED_TAGS] 个),确认
 *   按钮双条件门控,点确认即 UserTagPrefs 落盘(completed=true)并回调;
 * - 关闭/点外/返回 = 仅收起不落盘(未完成态下次进入再弹)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserTagSheet(
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    var gender by remember { mutableStateOf(UserTagPrefs.gender(context)) }
    var selected by remember { mutableStateOf(UserTagPrefs.tagIds(context)) }
    val canConfirm = gender != null && selected.size >= UserTagCatalog.MIN_SELECTED_TAGS

    Column(modifier.fillMaxSize()) {
        // 上半段露出底层页面,点外收起(同 InnerVoiceScreen 弹层惯例)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        // ── 白底弹层:标题行 + 性别节 + 喜好节 + 确认按钮 ──
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(24.dp))
                // 标题行:「选择标签…」+ 右侧「关闭」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选择标签，让 GouXiang 更懂你",
                        color = InkDark,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        color = InkGray,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onDismiss() }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(text = "选择你的性别", color = InkGray, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UserGender.entries.forEach { option ->
                        val selectedGender = gender == option
                        TagChip(
                            label = option.label,
                            emoji = option.emoji,
                            selected = selectedGender,
                            height = 46.dp,
                            fontSize = 16.sp,
                            onClick = { gender = option },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "选择你的喜好", color = InkGray, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "至少选${UserTagCatalog.MIN_SELECTED_TAGS}个",
                        color = InkGray,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Color(0xFFF4F4F4), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UserTagCatalog.TAGS.forEach { tag ->
                        TagChip(
                            label = tag.label,
                            emoji = tag.emoji,
                            selected = tag.id in selected,
                            height = 42.dp,
                            fontSize = 15.sp,
                            onClick = {
                                selected = if (tag.id in selected) {
                                    selected - tag.id
                                } else {
                                    selected + tag.id
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
            Spacer(Modifier.height(4.dp))
            // 确认按钮:性别已选 + 喜好≥4 才可点;确认即持久化并收起
            val g = gender
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .height(52.dp)
                    .background(
                        if (canConfirm) SheetYellow else Color(0xFFEDEDED),
                        RoundedCornerShape(26.dp),
                    )
                    .clickable(enabled = canConfirm) {
                        // 门控已保证 g 非空;lambda 内重新判空(智能跨不了参数表达式)
                        if (g != null) {
                            UserTagPrefs.save(context, g, selected)
                            onConfirmed()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "确认",
                    color = if (canConfirm) InkDark else Color(0xFFB8B8B8),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 弹层 chip(性别/喜好同款):emoji + 文本,选中黄底、未选白底描边。 */
@Composable
private fun TagChip(
    label: String,
    emoji: String,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(height)
            .background(if (selected) SheetYellow else Color.White, shape)
            .border(1.dp, if (selected) Color.Transparent else ChipBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = emoji, fontSize = fontSize)
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = InkDark, fontSize = fontSize, fontWeight = FontWeight.Medium)
    }
}
