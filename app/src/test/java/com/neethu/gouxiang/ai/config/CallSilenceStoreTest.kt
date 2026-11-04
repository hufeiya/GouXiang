package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 语音通话节奏双滑块(断句停顿/静默主动接话)的持久化 round-trip。 */
@RunWith(RobolectricTestRunner::class)
class CallSilenceStoreTest {

    @Test
    fun `call silence sliders persist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(2, AiConfig().callEndSilenceS) // 默认口径:断句 2s
        assertEquals(5, AiConfig().callIdleSpeakS) // 默认口径:接话 5s
        AiConfigStore.save(context, AiConfig(callEndSilenceS = 7, callIdleSpeakS = 0))
        val loaded = AiConfigStore.load(context)
        assertEquals(7, loaded.callEndSilenceS)
        assertEquals(0, loaded.callIdleSpeakS)
    }

    @Test
    fun `slider values clamp to 0-10`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiConfigStore.save(context, AiConfig(callEndSilenceS = 99, callIdleSpeakS = -3))
        assertEquals(10, AiConfigStore.load(context).callEndSilenceS)
        assertEquals(0, AiConfigStore.load(context).callIdleSpeakS)
    }
}
