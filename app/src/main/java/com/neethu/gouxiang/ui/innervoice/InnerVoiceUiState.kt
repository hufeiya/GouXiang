package com.neethu.gouxiang.ui.innervoice

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.data.InnerVoiceEntity

/**
 * 心声页 07 的 UI 状态。
 *
 * [items] 为倒序心声卡(最新在上,observe 流驱动,生成落库即上屏);
 * [generating] 驱动生成中的提示;[error] = 上一次生成失败
 * 的提示文案(生成链失败安全,只提示不崩溃;下一次成功清空)。
 */
@Immutable
data class InnerVoiceUiState(
    val loading: Boolean = false,
    val generating: Boolean = false,
    val characterName: String = "",
    val items: List<InnerVoiceEntity> = emptyList(),
    val imageResults: Map<Long, com.neethu.gouxiang.ui.ImageGenerationState> = emptyMap(),
    val error: String? = null,
    val automaticError: String? = null,
    /** 是否为该角色发现心声。 */
    val characterAutoEnabled: Boolean = true,
    /** 当前角色 id(开关回写目标)。 */
    val characterId: Long = 0,
    /** 加载失败(会话/角色不存在)。 */
    val loadFailed: Boolean = false,
)
