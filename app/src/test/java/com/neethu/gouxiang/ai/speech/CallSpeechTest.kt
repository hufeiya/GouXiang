package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.protocol.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音通话 F6 的口语文本层单测:多泡回复 → TTS 念稿 + 字幕摘要。
 * 纯函数零 Android 依赖(testOptions.isReturnDefaultValues 已开,Log 兜底可用)。
 */
class CallSpeechTest {

    @Test
    fun `text bubbles join with sentence dot and actions stripped`() {
        val (speech, seen) = CallSpeech.composeSpeechText(
            listOf(
                Bubble(Bubble.Kind.TEXT, "(凑过来)在忙吗", emptyList()),
                Bubble(Bubble.Kind.TEXT, "想你了(戳戳)", emptyList()),
            ),
        )
        assertEquals("在忙吗。想你了", speech)
        assertEquals(listOf("在忙吗", "想你了"), seen)
    }

    @Test
    fun `sticker img voice contribute to seen only not speech`() {
        val (speech, seen) = CallSpeech.composeSpeechText(
            listOf(
                Bubble(Bubble.Kind.TEXT, "给你看个宝贝"),
                Bubble(Bubble.Kind.STICKER, "歪头撒娇"),
                Bubble(Bubble.Kind.IMAGE, "床头的小猫"),
                Bubble(Bubble.Kind.VOICE, "悄悄话"),
            ),
        )
        assertEquals("给你看个宝贝", speech)
        assertEquals(listOf("给你看个宝贝", "歪头撒娇", "床头的小猫", "悄悄话"), seen)
    }

    @Test
    fun `non-text only reply yields empty speech but seen summary`() {
        val (speech, seen) = CallSpeech.composeSpeechText(listOf(Bubble(Bubble.Kind.STICKER, "捂脸")))
        assertEquals("", speech)
        assertEquals(listOf("捂脸"), seen)
        // 空念稿走兜底:字幕/TTS 总有内容
        assertEquals(CallSpeech.FALLBACK_SPEECH, CallSpeech.speechOrFallback(speech))
    }

    @Test
    fun `full and half width action parentheses both stripped`() {
        val speech = CallSpeech.stripActions("(靠在你肩头)今晚月色真美(小声)")
        assertEquals("今晚月色真美", speech)
    }

    @Test
    fun `fallback speech is non blank`() {
        assertTrue(CallSpeech.FALLBACK_SPEECH.isNotBlank())
    }
}
