package com.neethu.gouxiang.ui.derivatives

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.derivatives.ChatDerivativeOutput

/**
 * 手帐/小说/合照三页共享的 UI 状态与行模型(G 工作包 G1-G3;列表语义与
 * 番外页 [com.neethu.gouxiang.ui.sidestory.SidestoryUiState] 同构:记录实体
 * 倒序、一次性 error、详情开合)。
 */
@Immutable
data class ChatDerivativeUiState(
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val characterName: String = "",
    /** 会话内该类型记录(最新在前,observe 流驱动,落库即上屏)。 */
    val rows: List<ChatDerivativeEntity> = emptyList(),
    /** 本次页面操作的一次性错误(入队失败等);记录级失败在行上内联展示。 */
    val error: String? = null,
    /** 当前打开详情的记录 id(null=未打开)。 */
    val detailId: Long? = null,
) {
    /** 当前详情行(未知 id → null,详情层自动收起)。 */
    val detail: ChatDerivativeEntity? get() = detailId?.let { id -> rows.firstOrNull { it.id == id } }
}

/** 记录展示用的类型名(未知 kind 回落 kind 本身;红线下不出现商业字样)。 */
internal fun kindLabel(kind: String): String = when (kind) {
    ChatDerivativeEntity.KIND_HANDBOOK -> "手帐"
    ChatDerivativeEntity.KIND_NOVEL -> "小说"
    ChatDerivativeEntity.KIND_PHOTO -> "合照"
    else -> kind
}

/** 手帐详情的解析缓存(坏 JSON 回落空,列表不崩)。 */
internal fun handbookOf(record: ChatDerivativeEntity): ChatDerivativeOutput.Handbook? =
    ChatDerivativeOutput.handbookFromJson(record.bodyJson)

/** 小说详情的解析缓存。 */
internal fun novelOf(record: ChatDerivativeEntity): ChatDerivativeOutput.Novel? =
    ChatDerivativeOutput.novelFromJson(record.bodyJson)

/** 合照详情的用户描述。 */
internal fun photoDescriptionOf(record: ChatDerivativeEntity): String =
    ChatDerivativeOutput.photoDescriptionFromJson(record.bodyJson)
