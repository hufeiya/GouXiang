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
    fun `handbook images default off and roundtrip`() {
        // G1 手帐配图:默认关(与番外配图同口径),写后读往返
        val c = context()
        assertFalse(GenerationPrefs.handbookImages(c))
        GenerationPrefs.setHandbookImages(c, true)
        assertTrue(GenerationPrefs.handbookImages(c))
        GenerationPrefs.setHandbookImages(c, false)
        assertFalse(GenerationPrefs.handbookImages(c))
    }

    @Test
    fun `moments last day roundtrip and default`() {
        val c = context()
        assertEquals(-1L, GenerationPrefs.momentsLastDay(c))
        GenerationPrefs.setMomentsLastDay(c, 20372L)
        assertEquals(20372L, GenerationPrefs.momentsLastDay(c))
    }

    @Test
    fun `image settings are independent and persist separately`() {
        val c = context()
        assertTrue(GenerationPrefs.innerVoiceImages(c))
        assertTrue(GenerationPrefs.momentsImages(c))
        GenerationPrefs.setInnerVoiceImages(c, false)
        assertFalse(GenerationPrefs.innerVoiceImages(c))
        assertTrue(GenerationPrefs.momentsImages(c))
        GenerationPrefs.setMomentsImages(c, false)
        GenerationPrefs.setInnerVoiceImages(c, true)
        assertTrue(GenerationPrefs.innerVoiceImages(c))
        assertFalse(GenerationPrefs.momentsImages(c))
    }

    @Test
    fun `sidestory images default off and persist independently`() {
        // M0 收尾:番外配图键先行(默认关,分支随 F 工作包接入)
        val c = context()
        assertFalse(GenerationPrefs.sidestoryImages(c))
        GenerationPrefs.setSidestoryImages(c, true)
        assertTrue(GenerationPrefs.sidestoryImages(c))
        GenerationPrefs.setMomentsImages(c, false)
        assertTrue(GenerationPrefs.sidestoryImages(c))
    }
}
