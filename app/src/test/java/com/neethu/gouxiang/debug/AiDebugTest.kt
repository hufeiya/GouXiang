package com.neethu.gouxiang.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层解析器 JVM 单测(移植参照 AIAvatar-SDK 的 AiDebugTest):
 * 数值串容错、布尔变体、缺失 ai_cmd 返 null、weight clamp。
 */
class AiDebugTest {

    @Test
    fun `parses full command with all extras`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "STATE",
                EXTRA_AI_ARG to "过滤词",
                EXTRA_AI_WEIGHT to 0.7f,
            )
        )!!
        assertEquals("state", cmd.name)
        assertEquals("过滤词", cmd.arg)
        assertEquals(0.7f, cmd.weight)
        assertTrue(cmd.loop)
    }

    @Test
    fun `parses numeric extras`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "rewind",
                EXTRA_AI_X to 42f,
                EXTRA_AI_Y to -1f,
            )
        )!!
        assertEquals(42f, cmd.x)
        assertEquals(-1f, cmd.y)
        assertNull(cmd.z)
    }

    @Test
    fun `returns null without ai_cmd`() {
        assertNull(parseAiDebugCommand(emptyMap()))
        assertNull(parseAiDebugCommand(mapOf(EXTRA_AI_ARG to "hello")))
        assertNull(parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "   ")))
        assertNull(parseAiDebugCommand(mapOf(EXTRA_AI_CMD to 123))) // 非 String 不当命令
    }

    @Test
    fun `accepts numeric extras sent as strings`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "msgs",
                EXTRA_AI_X to "200",
            )
        )!!
        assertEquals(200f, cmd.x)
    }

    @Test
    fun `invalid numeric string is ignored`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "msgs",
                EXTRA_AI_X to "abc",
            )
        )!!
        assertNull(cmd.x)
    }

    @Test
    fun `loop accepts on off words and booleans`() {
        fun loopOf(value: Any?) = parseAiDebugCommand(
            mapOf(EXTRA_AI_CMD to "state", EXTRA_AI_LOOP to value)
        )!!.loop

        assertTrue(loopOf(null)) // 默认 true
        assertTrue(loopOf(true))
        assertTrue(loopOf("on"))
        assertTrue(loopOf("1"))
        assertFalse(loopOf(false))
        assertFalse(loopOf("off"))
        assertFalse(loopOf("0"))
    }

    @Test
    fun `weight is clamped to 0 and 1`() {
        val high = parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "s", EXTRA_AI_WEIGHT to "3.5"))!!
        assertEquals(1f, high.weight)
        val low = parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "s", EXTRA_AI_WEIGHT to -2))!!
        assertEquals(0f, low.weight)
    }

    @Test
    fun `blank arg becomes null`() {
        val cmd = parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "open_tab", EXTRA_AI_ARG to "  "))!!
        assertNull(cmd.arg)
    }

    @Test
    fun `command name is trimmed and lowercased`() {
        val cmd = parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "  Open_Chat "))!!
        assertEquals("open_chat", cmd.name)
    }

    @Test
    fun `help text lists all P0 commands`() {
        val help = aiDebugHelp()
        listOf(
            "state", "msgs", "contexts", "open_chat", "open_tab", "ai_config",
            "send_chat", "send_card", "parse", "retry", "rewind",
            "proactive", "proactive_fire",
            "memory_list", "memory_stats", "memory_forget", "memory_summarize",
            "status", "set_style", "set_length",
            "tasks", "task_retry", "task_cancel", "open_tasks", "close_tasks",
        ).forEach { cmdName ->
            assertTrue("help 缺命令 $cmdName", help.contains(cmdName))
        }
        // 热启动排查口径(M0 收尾工作包 4):溯源标签写入 help
        assertTrue(help.contains("AIDebug.Queue"))
    }
}
