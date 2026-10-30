package com.neethu.gouxiang.ui.innervoice

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import com.neethu.gouxiang.data.InnerVoiceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 心声页 07(基准 07-inner-voice.png / 07-inner-voice.xml dump):
 *
 * - 半屏底部弹层形态(dump:design_bottom_sheet [0,600][1080,2358] +
 *   touch_outside 点击收起),标题「Ta 的心声」居中 + 右上圆形 X 关闭;
 * - 心声卡(dump 每卡):左侧竖版配图(img_cover 166×266)+ 右列
 *   标题(tv_title,白粗)/ 副文案(tv_desc,弱白)/ 日期行(tv_expire);
 *   本地版范围 3:tv_expire 位只显示生成日期「yyyy.MM.dd」,
 *   原版「剩余 N 天」时效类文案不实现、不得出现;
 * - 配图为可选增强:生成时生图失败 → 该卡保持纯文字卡(左图位画占位),
 *   生图成功后异步回填(imageFile 落库 → observe 流自动上屏);
 * - 本地化补充:「生成心声」按钮(基于记忆与最近对话让角色写 2~4 条新心声);
 *   空态复用官方文案 memory_card_page_empty_title/desc(07 §2.1 直接复用组)。
 */
@Composable
fun InnerVoiceScreen(
    state: InnerVoiceUiState,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler { onDismiss() }

    Column(modifier.fillMaxSize()) {
        // 上半段露出角色主页(dump:touch_outside 可点区域),点外收起
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        // ── 底部弹层(3/4 屏高;dump design_bottom_sheet [0,600][1080,2358])──
        Column(
            Modifier
                .fillMaxWidth()
                .weight(3f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF5B6B84), Color(0xFF46536A), Color(0xFF3D4A5D)),
                    ),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ),
        ) {
            // ── 弹层标题行:「Ta 的心声」居中 + 右上圆形 X(dump:948,633)──
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .height(56.dp),
            ) {
                Text(
                    "Ta 的心声",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.16f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── 生成心声按钮(本地化补充;生成中禁用)────────────────
                item(key = "generate") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE84D8A).copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.generating) { onGenerate() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.generating) "心声生成中…" else "生成心声",
                            color = Color(0xFFFF8FB8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (state.error != null) {
                    item(key = "error") {
                        Text(
                            state.error ?: "",
                            color = Color(0xFFFFB4CD),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                state.automaticError?.let { error ->
                    item(key = "automatic-error") { Text(error, color = Color(0xFFFFB4CD), fontSize = 12.sp) }
                }
                // ── 心声卡列表(dump rv_events,倒序最新在上)──────────────
                items(state.items, key = { it.id }) { voice ->
                    VoiceCard(voice, state.imageResults[voice.id], onRetryImage)
                }
                if (state.items.isEmpty() && !state.generating && !state.loading) {
                    item(key = "empty") {
                        // 空态复用官方文案(memory_card_page_empty_title/_desc)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("🤍", fontSize = 34.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "暂无Ta的心声",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "将自动根据对话内容,生成你们的专属回忆,再多聊聊吧",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** 单张心声卡(dump 每卡:img_cover + tv_title + tv_desc + tv_expire)。 */
@Composable
private fun VoiceCard(voice: InnerVoiceEntity, image: com.neethu.gouxiang.ui.ImageGenerationState?, onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        // 左侧竖版配图(dump img_cover [77,810][243,1076] ≈ 166×266,同比例缩小);
        // 无图(纯文字卡兜底)画占位底 + 心形
        Box(
            Modifier
                .width(86.dp)
                .height(132.dp)
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (voice.imageFile.isNotBlank()) {
                AsyncImage(
                    model = java.io.File(voice.imageFile),
                    contentDescription = voice.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("💗", fontSize = 26.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                voice.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                voice.echo,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (voice.isStale) {
                Text("对话已回溯，此内容可能已过时", color = Color(0xFFFFCF88), fontSize = 12.sp)
            }
            com.neethu.gouxiang.ui.ImageRetryAction(image, voice.imageFile.isBlank() && !voice.isStale, Color(0xFFFFCF88), onRetryImage)
            Spacer(Modifier.height(10.dp))
            // 日期行(dump tv_expire 位置;范围 3:只显示日期,无「剩余 N 天过期」)
            Text(
                formatVoiceDate(voice.at),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
            )
        }
    }
}

/** 日期行文案:yyyy.MM.dd(对齐基准卡「2026.10.27」格式)。 */
internal fun formatVoiceDate(at: Long): String =
    SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(at))
