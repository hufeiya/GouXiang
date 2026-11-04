package com.neethu.gouxiang.ai.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断句器纯逻辑单测(2026-11-04 通话全双工):帧序列 → 起句/断句/barge-in 事件。
 * 全部喂合成的 320 采样帧 + 人工分类布尔,VAD 分类器本身不在测面上。
 * 一帧 = 320 采样 × 2 字节 = 640 字节 = 20ms。
 */
class UtteranceEndpointerTest {

    private val frame = ShortArray(320) // 内容不参与判定,长度即 20ms 口径

    /** 喂 n 帧并收集非空事件。 */
    private fun feed(ep: UtteranceEndpointer, n: Int, speech: Boolean): List<UtteranceEndpointer.Event> =
        (0 until n).mapNotNull { ep.onFrame(frame, speech) }

    @Test
    fun `short noise below start threshold does not trigger`() {
        val ep = UtteranceEndpointer()
        // 2 帧语音(< 起句阈值 3 帧)+ 回落
        assertNull(feed(ep, 2, speech = true).lastOrNull())
        assertNull(feed(ep, 5, speech = false).lastOrNull())
        assertFalse(ep.collecting)
    }

    @Test
    fun `sustained speech starts collection with preroll`() {
        val ep = UtteranceEndpointer()
        feed(ep, 10, speech = false) // 先安静,攒预滚(起句时连 300ms 预滚一起收)
        val events = feed(ep, 3, speech = true)
        val started = events.filterIsInstance<UtteranceEndpointer.Event.SpeechStarted>().single()
        assertFalse(started.isBargeIn)
        assertTrue(ep.collecting)
    }

    @Test
    fun `utterance ends after configured silence`() {
        val ep = UtteranceEndpointer().apply { endpointSilenceMs = 2_000 }
        feed(ep, 5, speech = false) // 预滚攒 5 帧
        feed(ep, 3, speech = true) // 起句(收集含预滚 5 + 起句 3 = 8 帧)
        // 说话 19 帧(静音清零)→ 安静 2s:第 99 帧还差 20ms 不断,第 100 帧断
        assertNull(feed(ep, 19, speech = true).lastOrNull())
        assertNull(feed(ep, 99, speech = false).lastOrNull())
        val utt = feed(ep, 1, speech = false)
            .filterIsInstance<UtteranceEndpointer.Event.Utterance>().single()
        assertEquals((5 + 3 + 19 + 100) * 640, utt.pcm.size)
        assertFalse(ep.collecting)
    }

    @Test
    fun `endpoint silence floor holds for zero setting`() {
        val ep = UtteranceEndpointer().apply { endpointSilenceMs = 0 } // 说完立刻
        feed(ep, 3, speech = true) // 起句
        // 内置 400ms 滞回:静音 380ms(19 帧)不断,420ms(再 2 帧)断
        assertNull(feed(ep, 19, speech = false).lastOrNull())
        val events = feed(ep, 2, speech = false)
        assertNotNull(events.filterIsInstance<UtteranceEndpointer.Event.Utterance>().singleOrNull())
    }

    @Test
    fun `speech during tts requires barge-in window majority`() {
        val ep = UtteranceEndpointer().apply { ttsPlaying = true }
        feed(ep, 10, speech = false)
        // TTS 回声残段:偶发 3 帧语音 + 静音,滑窗多数帧不足 6/10 → 不起句不打断
        assertNull(feed(ep, 3, speech = true).lastOrNull())
        assertNull(feed(ep, 5, speech = false).lastOrNull())
        // 用户真开口:滑窗里语音过半 → barge-in 起句
        val events = feed(ep, 7, speech = true)
        val started = events.filterIsInstance<UtteranceEndpointer.Event.SpeechStarted>().single()
        assertTrue(started.isBargeIn)
        assertTrue(ep.collecting)
    }

    @Test
    fun `max utterance duration force-cuts`() {
        val ep = UtteranceEndpointer()
        feed(ep, 3, speech = true) // 起句(收集从这 3 帧起算)
        // 持续说满 60s(3000 帧 - 已收 3 帧)→ 强制断句,不等静音
        val events = feed(ep, (UtteranceEndpointer.MAX_UTTERANCE_MS / 20).toInt() - 3, speech = true)
        assertNotNull(events.filterIsInstance<UtteranceEndpointer.Event.Utterance>().singleOrNull())
        assertFalse(ep.collecting)
    }

    @Test
    fun `two utterances do not leak frames between them`() {
        val ep = UtteranceEndpointer().apply { endpointSilenceMs = 0 } // 400ms 滞回断句
        feed(ep, 3, speech = true) // 句一:收集 3 帧
        val first = feed(ep, 20, speech = false)
            .filterIsInstance<UtteranceEndpointer.Event.Utterance>().single() // 3+20=23 帧断出
        assertEquals(23 * 640, first.pcm.size)
        feed(ep, 3, speech = true) // 句二起句(收集重开,不带句一残留)
        feed(ep, 2, speech = true)
        val second = feed(ep, 20, speech = false)
            .filterIsInstance<UtteranceEndpointer.Event.Utterance>().single()
        assertEquals(25 * 640, second.pcm.size)
    }
}
