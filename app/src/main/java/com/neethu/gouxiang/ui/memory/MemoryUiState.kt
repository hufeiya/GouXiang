package com.neethu.gouxiang.ui.memory

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.chat.MemoryBook
import com.neethu.gouxiang.chat.MemoryCapacity

/**
 * 记忆页 05 的 UI 状态(状态机对齐 03 §3.2g 的七态骨架,本地版收敛:
 * Loading=进页加载、Generating=总结中、Empty=全空、Show=正常内容;
 * 门禁两态(NoRights/NoAssets)本地版恒不出现,不建模)。
 */
@Immutable
data class MemoryUiState(
    val loading: Boolean = false,
    val generating: Boolean = false,
    val characterName: String = "",
    /** 画像两件套(Ta 心中的我;null/空 = 暂无)。 */
    val profileName: String? = null,
    val profileIdentity: String? = null,
    /** 事件条目(带容量分带,faded=逐渐模糊)。 */
    val bandedEvents: List<MemoryCapacity.Banded> = emptyList(),
    val capacity: MemoryCapacity.CapacityState = MemoryCapacity.CapacityState(
        MemoryCapacity.capacity(), 0, 0,
    ),
    /** 加载失败(会话/角色不存在)。 */
    val loadFailed: Boolean = false,
) {
    val profileEmpty: Boolean get() = profileName.isNullOrBlank() && profileIdentity.isNullOrBlank()
}
