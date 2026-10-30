package com.neethu.gouxiang.ui.sidestory

import com.neethu.gouxiang.data.SidestoryRecordEntity
import com.neethu.gouxiang.sidestory.SidestoryCatalog

/**
 * 番外剧场页 UI 状态(F 工作包 F3/F4):
 *
 * - [rows] = 会话内记录行(记录实体 + 目录文案回填;模板已下架时回落 key);
 * - [error] = 本次页面操作的一次性错误(入队失败等);记录级失败状态在
 *   各行 [SidestoryRecordEntity.state]/[failureReason] 上,列表内联展示可重试;
 * - [detailId] = 当前打开详情的记录 id(null=未打开)。
 */
data class SidestoryUiState(
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val characterName: String = "",
    val rows: List<SidestoryRecordRow> = emptyList(),
    val error: String? = null,
    val detailId: Long? = null,
) {
    /** 当前详情行(未知 id → null,详情层自动收起)。 */
    val detail: SidestoryRecordRow? get() = detailId?.let { id -> rows.firstOrNull { it.record.id == id } }
}

/** 番外页一行记录(实体 + 目录文案;目录升级后旧 key 回落 key 本身)。 */
data class SidestoryRecordRow(
    val record: SidestoryRecordEntity,
    val templateName: String,
    val templateSubtitle: String,
) {
    companion object {
        fun of(record: SidestoryRecordEntity): SidestoryRecordRow {
            val entry = SidestoryCatalog.byKey(record.templateKey)
            return SidestoryRecordRow(
                record = record,
                templateName = entry?.name ?: record.templateKey,
                templateSubtitle = entry?.subtitle ?: "",
            )
        }
    }
}
