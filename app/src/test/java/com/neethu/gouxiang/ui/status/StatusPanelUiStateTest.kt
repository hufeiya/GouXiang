package com.neethu.gouxiang.ui.status

import com.neethu.gouxiang.data.CharacterStatusEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * E 工作包 E3:状态面板行模型与文案纯函数单测(来源标签/时间标签/分组)。
 */
class StatusPanelUiStateTest {

    private fun record(
        id: Long = 1L,
        sourceKind: String = CharacterStatusEntity.SOURCE_TURN,
        state: String = CharacterStatusEntity.STATE_ACTIVE,
        generatedAt: Long = 0,
        start: Long? = 3L,
        end: Long? = 9L,
    ) = CharacterStatusEntity(
        id = id, characterId = 1, conversationId = 2, emoji = "🤔", mood = "开心",
        doing = "在等你的消息", thought = "他在忙什么", sourceKind = sourceKind,
        sourceConversationId = 2, sourceTurnId = "turn-1", sourceStartMessageId = start,
        sourceEndMessageId = end, sourceVersion = 0, state = state, generatedAt = generatedAt,
    )

    // ── 来源文案 ────────────────────────────────────────────────────

    @Test
    fun `turn source shows message range`() {
        assertEquals("来自聊天回合(消息 #3~#9)", statusSourceLabel(record()))
        assertEquals("来自聊天回合(消息 #9)", statusSourceLabel(record(start = null)))
        assertEquals("来自聊天回合", statusSourceLabel(record(start = null, end = null)))
    }

    @Test
    fun `manual source labeled as debug injection`() {
        assertEquals("手动注入(调试)", statusSourceLabel(record(sourceKind = CharacterStatusEntity.SOURCE_MANUAL)))
    }

    @Test
    fun `stale records marked with rewind note`() {
        assertEquals(
            "来自聊天回合(消息 #3~#9) · 已随回溯失效",
            statusSourceLabel(record(state = CharacterStatusEntity.STATE_STALE)),
        )
    }

    // ── 时间文案 ────────────────────────────────────────────────────

    @Test
    fun `time labels follow today yesterday and older tiers`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 31, 9, 5)
        }.timeInMillis
        val yesterday = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 23, 1)
        }.timeInMillis
        val older = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 8, 30)
        }.timeInMillis
        val lastYear = Calendar.getInstance().apply {
            set(2025, Calendar.DECEMBER, 31, 8, 30)
        }.timeInMillis
        assertEquals("今天 09:05", statusTimeLabel(today, now))
        assertEquals("昨天 23:01", statusTimeLabel(yesterday, now))
        assertEquals("10月1日 08:30", statusTimeLabel(older, now))
        assertEquals("2025年12月31日 08:30", statusTimeLabel(lastYear, now))
    }

    // ── 面板分组 ────────────────────────────────────────────────────

    @Test
    fun `panel rows group current history and stale sections`() {
        val state = StatusPanelUiState(
            characterName = "苏馨玥",
            rows = listOf(
                toStatusRow(record(id = 3), nowMs = 10), // 最新 active → 当前卡
                toStatusRow(record(id = 2), nowMs = 10),
                toStatusRow(record(id = 1, state = CharacterStatusEntity.STATE_STALE), nowMs = 10),
            ),
        )
        assertEquals(3L, state.current!!.id)
        assertFalse(state.current!!.stale)
        // 时间轴(不含当前卡):仅 id=2
        assertEquals(listOf(2L), state.rows.filter { !it.stale }.drop(1).map { it.id })
        assertEquals(listOf(1L), state.staleRows.map { it.id })
        assertFalse(state.empty)
        // 行构造带来源与时间文案
        assertTrue(state.rows.single { it.id == 1L }.sourceLabel.contains("已随回溯失效"))
    }

    @Test
    fun `empty state has no current`() {
        val state = StatusPanelUiState(characterName = "苏馨玥")
        assertTrue(state.empty)
        assertEquals(null, state.current)
        assertTrue(state.staleRows.isEmpty())
    }
}
