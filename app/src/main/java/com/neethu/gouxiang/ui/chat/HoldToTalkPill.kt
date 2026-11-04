package com.neethu.gouxiang.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 按住说话手势态(2026-10-31 口径):原位录音 / 上划出按钮取消 / 侧移输入。 */
internal enum class HoldMode { None, Recording, Cancel, Input }

/** 波形条数(按住中的 |||||;固定窗滚动,静音时呈齐排矮条)。 */
private const val HoldWaveBars = 16

/**
 * 按住中的实时波形:60ms 一格滚动样本窗,新样本右进左出,经典语音输入观感。
 * [level] 经 rememberUpdatedState 供循环读最新值(静音也持续滚动)。
 */
@Composable
private fun HoldWaveform(level: Float, barColor: Color, modifier: Modifier = Modifier) {
    val currentLevel by rememberUpdatedState(level)
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            samples.add(currentLevel)
            while (samples.size > HoldWaveBars) samples.removeAt(0)
            delay(60)
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(HoldWaveBars) { i ->
            // 旧样本在左,新样本在右(窗未满时左侧补静音格)
            val s = samples.getOrNull(i + samples.size - HoldWaveBars) ?: 0f
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + 26 * s.coerceIn(0f, 1f)).dp)
                    .background(barColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * 「按住说话」胶囊(2026-11-04 自普通聊天页 InputBar 抽出共享,手机聊天页同款复用):
 *
 * - 原地松手 = ASR 转写直接发送;按住中变黑色胶囊 + 实时波形并轻微放大;
 * - 上划出按钮 = 取消态:胶囊变红 +「松手取消」,松手丢弃;
 * - 侧移(左右皆可)= 输入态:黑→白渐变,松手把语音转「(动作)」括号文本填
 *   输入框 —— 仅 [sideInputEnabled]=true(普通聊天页)启用;手机页传 false
 *   (无侧滑),手势与提示文案随之只剩 原位发送/上划取消;
 * - 轻点/识别失败落「没听清」提示;识别中显示状态,防重入。
 * [enabled] = false 时按下不响应(普通页开场白/加载期禁用;手机页保持可按)。
 */
@Composable
internal fun HoldToTalkPill(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 是否启用侧移输入(动作场景括号文本);手机聊天页=false 无侧滑。 */
    sideInputEnabled: Boolean = true,
    onHoldStart: () -> Unit = {},
    onHoldRelease: (MainViewModel.VoiceInputAction) -> Unit = {},
    onHoldModeSwitch: (Boolean) -> Unit = {},
    voiceLevel: Float = 0f,
    recognizing: Boolean = false,
) {
    // 按住手势态(UI 局部实时;VM 只收松手时的最终去向)
    var holdMode by remember { mutableStateOf(HoldMode.None) }
    var sideProgress by remember { mutableFloatStateOf(0f) }

    val holding = holdMode != HoldMode.None
    // Material 动效:按住胶囊轻微放大(spring 弹性),侧移颜色按位移实时渐变
    val pillScale by animateFloatAsState(
        targetValue = if (holding) 1.03f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "holdPillScale",
    )
    val pillColor = when (holdMode) {
        HoldMode.None -> Color.Transparent
        HoldMode.Recording -> Color(0xFF1A1A1A)
        HoldMode.Input -> lerp(Color(0xFF1A1A1A), Color.White, sideProgress)
        HoldMode.Cancel -> Color(0xFFFF3B30)
    }
    val barColor = when (holdMode) {
        HoldMode.None -> Color.Transparent
        HoldMode.Recording, HoldMode.Cancel -> Color.White
        HoldMode.Input -> lerp(Color.White, Color(0xFF1A1A1A), sideProgress)
    }
    val holdHint = when (holdMode) {
        HoldMode.Recording -> if (sideInputEnabled) "上移取消，侧移输入(动作场景)" else "上移取消"
        HoldMode.Input -> "正在输入(动作场景)"
        HoldMode.Cancel -> "松手取消"
        HoldMode.None -> ""
    }
    val hintColor = if (holdMode == HoldMode.Cancel) Color(0xFFFF6B61) else Color.White.copy(alpha = 0.92f)

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .graphicsLayer {
                    scaleX = pillScale
                    scaleY = pillScale
                }
                .clip(RoundedCornerShape(20.dp))
                .background(pillColor)
                .pointerInput(enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!enabled) return@awaitEachGesture
                        down.consume()
                        onHoldStart()
                        holdMode = HoldMode.Recording
                        sideProgress = 0f
                        val downX = down.position.x
                        val sideThreshold = 64.dp.toPx()
                        // 滞回带:进入侧移 64dp,退回原位要 <44dp(阈值附近抖动
                        // 反复互切会把录音切得粉碎,2026-10-31 分段口径防抖)
                        val backThreshold = 44.dp.toPx()
                        val exitSlop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                // 松手:按手势终态分发(原位=发送/上划=取消/侧移=输入)
                                val action = when (holdMode) {
                                    HoldMode.Cancel -> MainViewModel.VoiceInputAction.CANCEL
                                    HoldMode.Input -> MainViewModel.VoiceInputAction.INPUT
                                    else -> MainViewModel.VoiceInputAction.SEND
                                }
                                holdMode = HoldMode.None
                                sideProgress = 0f
                                onHoldRelease(action)
                                break
                            }
                            val pos = change.position
                            val dx = abs(pos.x - downX)
                            // 按指针位置实时重算:出按钮上方=取消,侧移带滞回
                            // (进 64dp / 退 44dp),其余回原位录音;手机页无侧移,
                            // [sideInputEnabled]=false 时 dx 恒不触发输入态
                            val newMode = when {
                                pos.y < -exitSlop -> HoldMode.Cancel
                                holdMode == HoldMode.Input ->
                                    if (dx < backThreshold) HoldMode.Recording else HoldMode.Input
                                sideInputEnabled && dx >= sideThreshold -> HoldMode.Input
                                else -> HoldMode.Recording
                            }
                            if (newMode != holdMode) {
                                holdMode = newMode
                                // 段边界:原位/侧移互切封段,段归属新态起点
                                // (Cancel 不参与段切分,松手终态统一丢弃)
                                when (newMode) {
                                    HoldMode.Input -> onHoldModeSwitch(true)
                                    HoldMode.Recording -> onHoldModeSwitch(false)
                                    HoldMode.Cancel -> {}
                                    HoldMode.None -> {}
                                }
                            }
                            sideProgress =
                                if (holdMode == HoldMode.Cancel) 0f
                                else (dx / sideThreshold).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                recognizing -> Text(
                    "识别中…",
                    color = Color(0xFF3A3A3A),
                    fontSize = 16.sp,
                )
                holdMode != HoldMode.None -> HoldWaveform(
                    level = voiceLevel,
                    barColor = barColor,
                )
                else -> Text(
                    "按住说话",
                    color = Color(0xFF3A3A3A),
                    fontSize = 17.sp,
                )
            }
        }
        // 按住中:胶囊上方浮一行小字提示(浮层不挤布局;Material 淡入+上滑动效;
        // 偏移以胶囊顶为基准,普通页/手机页输入条上下留白同构,浮出位置一致)
        AnimatedVisibility(
            visible = holding,
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-48).dp),
        ) {
            Text(
                holdHint,
                color = hintColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
