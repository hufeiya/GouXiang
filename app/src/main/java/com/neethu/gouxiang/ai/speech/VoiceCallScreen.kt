package com.neethu.gouxiang.ai.speech

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import coil3.compose.AsyncImage
import java.io.File

/**
 * 语音通话页 12(基准 12-voice-call.png / 12-voice-call.xml,全屏覆盖层):
 *
 * - 全屏角色照片(iv_pic)+ 顶部压暗遮罩(top_mask)+ 底部渐晕(iv_bg);
 * - 顶栏:左上 X(button_toolbar_navigation)+ 居中名字(tv_toolbar_title);
 * - 大字幕(astv_chat 区域):TTS 同步口语词,白字大号居中;
 * - 底部操作区(ll_audio_operation_wrapper):提示条(tv_tips:聆听中/识别中/
 *   思考中/点击打断)+ 「按住说话」条 + 红色挂断键(iv_end_call);
 * - SPEAKING 态整页可点 = 打断(停播放回聆听);
 * - 小型调试输入条(验收用,不显眼):只有聆听态可输入回车/发送。
 */
@Composable
fun VoiceCallScreen(
    engine: VoiceCallEngine,
    name: String,
    avatarFile: String?,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by engine.state.collectAsState()

    BackHandler { onHangUp() }
    val requestMicrophonePermission =
        com.neethu.gouxiang.ui.permission.rememberMicrophonePermissionRequest()
    LaunchedEffect(Unit) { requestMicrophonePermission() }
    // 进页即「聆听中」/退页停一切(引擎由调用方按 conversationId 缓存复用)
    LaunchedEffect(Unit) { engine.start() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF23232B))
            // SPEAKING 态整页点击 = 打断(点字幕/照片空白处均可;挂断键自管点击)
            .pointerInput(state.phase) {
                detectTapGestures {
                    if (state.phase == VoiceCallEngine.Phase.SPEAKING) engine.interrupt()
                }
            },
    ) {
        // ── 全屏角色照片底 ────────────────────────────────────────────
        if (avatarFile != null) {
            AsyncImage(
                model = File(avatarFile),
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
        // 顶部压暗(对齐 top_mask [0,0][1080,332])
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )
        // 底部渐晕(对齐 iv_bg [0,1137][1080,2400])
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── 顶栏:X + 名字(参考页 toolbar 居中标题)─────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onHangUp() },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    name,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(26.dp)) // 右占位,标题居中
            }

            // ── 大字幕(TTS 同步;占中部偏下,白字大号)──────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (state.caption.isNotEmpty()) {
                    Text(
                        state.caption,
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 40.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 16.dp),
                    )
                }
            }

            // 泡摘要(贴纸/图片等不可念段的描述,小字淡显,验证回复确实来了)
            if (state.seenLines.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    state.seenLines.take(3).forEach { line ->
                        Text(
                            "· $line",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── 底部操作区:提示条 + 按住说话 + 挂断键 ────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 34.dp), // 底部给调试输入条留带,不与挂断键叠压
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.tipsText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                )
                if (state.notice.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.notice,
                        color = Color(0xFFFFB3C1),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 按住说话条(语音输入;按住中高亮)
                val hold = state.holdActive
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(46.dp)
                        .background(
                            if (hold) Color(0xFFE84D8A) else Color(0xFF3A3A44),
                            RoundedCornerShape(23.dp),
                        )
                        .pointerInput(state.phase) {
                            detectTapGestures(
                                onPress = {
                                    if (requestMicrophonePermission()) {
                                        engine.holdStart()
                                        try {
                                            awaitRelease()
                                        } finally {
                                            engine.holdRelease()
                                        }
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (hold) "松开 发送" else "按住 说话",
                        color = Color.White,
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.height(22.dp))
                // 红色挂断键(参考页 [440,1996][639,2195] ≈ 199dp 圆钮)
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .background(Color(0xFFE5484D), CircleShape)
                        .clickable { onHangUp() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = "挂断",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        // ── 小型调试输入条(验收用,贴屏幕最底,不显眼)──────────────────
        DebugInput(
            enabled = state.phase == VoiceCallEngine.Phase.LISTENING && state.holdActive.not(),
            onSend = { engine.sendDebugText(it) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .statusBarsPadding()
                .navigationBarsPadding(),
        )
    }
}

/** 调试输入(验收用):半透明窄条 + 发送箭头,聆听态可用。 */
@Composable
private fun DebugInput(
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = Color(0xFFEDEDF2), fontSize = 12.sp),
            cursorBrush = SolidColor(Color(0xFFE84D8A)),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "调试输入(验收)",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.Send,
            contentDescription = "发送(调试)",
            tint = if (enabled && draft.isNotBlank()) Color.White.copy(alpha = 0.8f)
            else Color.White.copy(alpha = 0.25f),
            modifier = Modifier
                .size(20.dp)
                .clickable {
                    if (enabled && draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
        )
    }
}
