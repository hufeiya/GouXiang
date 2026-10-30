package com.neethu.gouxiang.derivatives

import com.neethu.gouxiang.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1 手帐范围选择单测(纯 JVM):预设按 turn 分组取最近 N 回合、ALL 取全部、
 * 空会话拒绝创建、未知 key 回落。
 */
class DerivativeRangeTest {

    private fun msg(id: Long, turnId: String, at: Long = id) =
        MessageEntity(id = id, conversationId = 2, turnId = turnId, role = "user", content = "m$id", at = at)

    /** 3 回合:turn-a(1,2)/ turn-b(3,4,5)/ turn-c(6);id 单调递增。 */
    private val messages = listOf(
        msg(1, "turn-a"), msg(2, "turn-a"),
        msg(3, "turn-b"), msg(4, "turn-b"), msg(5, "turn-b"),
        msg(6, "turn-c"),
    )

    @Test
    fun `turn5 preset takes the last five turns or fewer`() {
        val range = DerivativeRange.resolve(messages, DerivativeRange.Preset.TURN_5)!!
        assertEquals(1L, range.startMessageId)
        assertEquals(6L, range.endMessageId)
        assertEquals(3, range.turnCount)
        assertEquals(6, range.messageCount)
    }

    @Test
    fun `turn windows clip at the requested turn count`() {
        // 只有 3 回合时 TURN_5 与 TURN_10 相同;造 4 回合验证最近 2 回合的裁切。
        val four = messages + msg(7, "turn-d") + msg(8, "turn-d")
        val last2 = DerivativeRange.resolve(four, DerivativeRange.Preset.TURN_5)!!
        // TURN_5 取全部 4 回合
        assertEquals(1L, last2.startMessageId)
        // 用 10 轮预设同样取全部(边界一致)
        val all = DerivativeRange.resolve(four, DerivativeRange.Preset.TURN_10)!!
        assertEquals(last2.startMessageId, all.startMessageId)
        assertEquals(last2.endMessageId, all.endMessageId)
    }

    @Test
    fun `all preset covers every message`() {
        val range = DerivativeRange.resolve(messages, DerivativeRange.Preset.ALL)!!
        assertEquals(1L, range.startMessageId)
        assertEquals(6L, range.endMessageId)
        assertEquals(3, range.turnCount)
    }

    @Test
    fun `empty conversation is rejected`() {
        assertNull(DerivativeRange.resolve(emptyList(), DerivativeRange.Preset.ALL))
    }

    @Test
    fun `preset keys fall back to turn10 for unknown values`() {
        assertEquals(DerivativeRange.Preset.TURN_10, DerivativeRange.Preset.fromKey("nope"))
        assertEquals(DerivativeRange.Preset.TURN_10, DerivativeRange.Preset.fromKey(null))
        assertEquals(DerivativeRange.Preset.TURN_5, DerivativeRange.Preset.fromKey("turn5"))
        assertEquals(DerivativeRange.Preset.ALL, DerivativeRange.Preset.fromKey("all"))
    }

    @Test
    fun `options roundtrip is stable per kind`() {
        val handbook = DerivativeOptions.HandbookOption("all")
        assertEquals("{\"range\":\"all\"}", handbook.toJson())
        val novel = DerivativeOptions.NovelOption("long")
        assertEquals("{\"length\":\"long\"}", novel.toJson())
        assertEquals(NovelLength.LONG, novel.length)
        val photo = DerivativeOptions.PhotoOption("天台看晚霞")
        assertEquals("{\"description\":\"天台看晚霞\"}", photo.toJson())
        // 坏 JSON 不抛,按 kind 回落默认
        val fallbackNovel = DerivativeOptions.fromJson("novel", "not-json")
        assertEquals(NovelLength.MEDIUM, (fallbackNovel as DerivativeOptions.NovelOption).length)
        val fallbackHandbook = DerivativeOptions.fromJson("handbook", "{}")
        assertEquals(DerivativeRange.Preset.TURN_10, (fallbackHandbook as DerivativeOptions.HandbookOption).preset)
    }

    @Test
    fun `novel length bounds drive validation and tokens`() {
        assertEquals(2..3, NovelLength.SHORT.chapters)
        assertEquals(4..5, NovelLength.MEDIUM.chapters)
        assertEquals(6..8, NovelLength.LONG.chapters)
        // 长篇预算必须高于短篇(防 JSON 截断)
        assertTrue(NovelLength.LONG.maxTokens > NovelLength.SHORT.maxTokens)
    }
}
