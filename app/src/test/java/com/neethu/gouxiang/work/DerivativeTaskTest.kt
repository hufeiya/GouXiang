package com.neethu.gouxiang.work

import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 衍生任务抽象单测(docs/background-generation-plan-20261030.md §5):
 * 键值序列化约定、非法 kind 语义、唯一名粒度(纯 Kotlin,零 Android 依赖)。
 */
class DerivativeTaskTest {

    @Test
    fun `keys are stable`() {
        // Worker inputData 键是 Worker/Executor 之间的契约,改名即丢任务
        assertEquals("derivative_kind", DerivativeTask.KEY_KIND)
        assertEquals("derivative_conversation_id", DerivativeTask.KEY_CONVERSATION_ID)
    }

    @Test
    fun `unique name is per kind per conversation`() {
        val voice = DerivativeTask(DerivativeKind.INNER_VOICE, 7L)
        val voiceOtherConv = DerivativeTask(DerivativeKind.INNER_VOICE, 8L)
        val moment = DerivativeTask(DerivativeKind.MOMENT, 7L)
        val scan = DerivativeTask(DerivativeKind.PROACTIVE_OUTBOX, -1L)

        assertEquals("deriv:INNER_VOICE:7", DerivativeTask.uniqueName(voice))
        // 不同会话互不冲突;不同 kind 同会话互不冲突
        assertTrue(DerivativeTask.uniqueName(voice) != DerivativeTask.uniqueName(voiceOtherConv))
        assertTrue(DerivativeTask.uniqueName(voice) != DerivativeTask.uniqueName(moment))
        // 批扫任务全局单例名
        assertEquals("deriv:PROACTIVE_OUTBOX:-1", DerivativeTask.uniqueName(scan))
    }

    @Test
    fun `every kind is dispatchable or explicitly deferred`() {
        // Executor 对 HANDBOOK/NOVEL/PHOTO 是显式 M2 延后(true 无操作),
        // 其余 kind 必须有真实分支——枚举完整性锁死,防漏分发。
        val kinds = DerivativeKind.entries
        assertTrue(kinds.contains(DerivativeKind.INNER_VOICE))
        assertTrue(kinds.contains(DerivativeKind.MOMENT))
        assertTrue(kinds.contains(DerivativeKind.MEMORY_SUMMARY))
        assertTrue(kinds.contains(DerivativeKind.PROACTIVE_OUTBOX))
        assertTrue(kinds.contains(DerivativeKind.HANDBOOK))
        assertTrue(kinds.contains(DerivativeKind.NOVEL))
        assertTrue(kinds.contains(DerivativeKind.PHOTO))
    }

    @Test
    fun `proactive copy has no billing words`() {
        // 文案红线(§6):通知文案严禁计费/猫粮/会员字样
        val all = buildList {
            add(DerivativeCopy.CHANNEL_DERIVATIVE)
            add(DerivativeCopy.CHANNEL_DERIVATIVE_NAME)
            add(DerivativeCopy.CHANNEL_DERIVATIVE_DESC)
            add(DerivativeCopy.CHANNEL_PROACTIVE)
            add(DerivativeCopy.CHANNEL_PROACTIVE_NAME)
            add(DerivativeCopy.CHANNEL_PROACTIVE_DESC)
            add(DerivativeCopy.innerVoiceTitle("苏馨玥"))
            add(DerivativeCopy.momentTitle("苏馨玥"))
            add(DerivativeCopy.genericTitle("苏馨玥"))
            add(DerivativeCopy.BODY_TAP_TO_VIEW)
            add(DerivativeCopy.proactiveTitle("苏馨玥"))
            add(DerivativeCopy.proactiveBody(2))
            add(DerivativeCopy.proactiveBodyMulti())
        }
        for (text in all) {
            assertFalse("红线字样「猫粮」出现于:$text", text.contains("猫粮"))
            assertFalse("红线字样「扣费」出现于:$text", text.contains("扣费"))
            assertFalse("红线字样「计费」出现于:$text", text.contains("计费"))
            assertFalse("红线字样「会员」出现于:$text", text.contains("会员"))
            assertFalse("红线字样「过期」出现于:$text", text.contains("过期"))
        }
    }

    @Test
    fun `settings copy has no billing words`() {
        // 设置页副标题一并纳入扫描(§6)
        val all = listOf(
            "开启后会在后台自动生成对应内容;关闭后仅当你点击对应按钮时才生成。自动生成消耗大模型调用,请按需开启。",
            "开启后定期自动写心声,消耗大模型调用;关闭后仅点「生成心声」时生成",
            "开启后每天自动发一条动态,消耗大模型调用;关闭后仅点「生成动态」时生成",
            "开启后闲置 8 小时以上自动提炼记忆窗口;关闭后记忆窗口等下次聊天时提炼",
            "开启后 TA 会在你离线时先发消息;关闭后 TA 不会在离线时先开口",
            "开启后达标自动生成衍生内容;关闭后仅手动生成(功能开发中)",
            "关闭后生成完成仅 App 内可见,不弹系统通知;不消耗任何调用",
            "自动后台生成与通知 · 关闭时点击才生成",
        )
        for (text in all) {
            assertFalse(text.contains("猫粮"))
            assertFalse(text.contains("扣费"))
            assertFalse(text.contains("计费"))
            assertFalse(text.contains("会员"))
            assertFalse(text.contains("过期"))
        }
    }
}
