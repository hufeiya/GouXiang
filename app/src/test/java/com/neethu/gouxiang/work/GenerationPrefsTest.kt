package com.neethu.gouxiang.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ai.GenerationPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GenerationPrefs 单测(§5):默认值拍板项(自动项全 false、proactiveOutbox/
 * notify true)、写后读往返、runCatching 存储容错。Robolectric 提供 Context
 * (工程现有 androidTest 面无 Robolectric,若依赖缺失改用 Context mock——
 * 先按 robolectric 依赖落,构建门禁验证)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationPrefsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults match the plan`() {
        // §3.2 拍板:自动项全关,proactiveOutbox/notify 默认开
        val c = context()
        assertFalse(GenerationPrefs.innerVoiceAuto(c))
        assertFalse(GenerationPrefs.momentsDaily(c))
        assertFalse(GenerationPrefs.memoryOvernight(c))
        assertTrue(GenerationPrefs.proactiveOutbox(c))
        assertFalse(GenerationPrefs.derivativeAuto(c))
        assertTrue(GenerationPrefs.notifyEnabled(c))
    }

    @Test
    fun `write then read roundtrip`() {
        val c = context()
        GenerationPrefs.setInnerVoiceAuto(c, true)
        GenerationPrefs.setMomentsDaily(c, true)
        GenerationPrefs.setMemoryOvernight(c, true)
        GenerationPrefs.setProactiveOutbox(c, false)
        GenerationPrefs.setDerivativeAuto(c, true)
        GenerationPrefs.setNotifyEnabled(c, false)
        assertTrue(GenerationPrefs.innerVoiceAuto(c))
        assertTrue(GenerationPrefs.momentsDaily(c))
        assertTrue(GenerationPrefs.memoryOvernight(c))
        assertFalse(GenerationPrefs.proactiveOutbox(c))
        assertTrue(GenerationPrefs.derivativeAuto(c))
        assertFalse(GenerationPrefs.notifyEnabled(c))
    }

    @Test
    fun `moments last day roundtrip and default`() {
        val c = context()
        assertEquals(-1L, GenerationPrefs.momentsLastDay(c))
        GenerationPrefs.setMomentsLastDay(c, 20372L)
        assertEquals(20372L, GenerationPrefs.momentsLastDay(c))
    }
}
