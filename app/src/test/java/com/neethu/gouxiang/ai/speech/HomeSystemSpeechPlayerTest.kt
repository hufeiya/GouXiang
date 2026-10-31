package com.neethu.gouxiang.ai.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSystemSpeechPlayerTest {
    @Test fun splitsLongTextWithoutBreakingSurrogatePairsOrDroppingCharacters() {
        val text = "你好😀世界😀再见"
        val parts = splitSystemSpeech(text, 3)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 3 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
    }

    @Test fun blankTextAndInvalidLimitProduceNoUtterance() {
        assertTrue(splitSystemSpeech("  ", 4).isEmpty())
        assertTrue(splitSystemSpeech("你好", 1).isEmpty())
    }
}
