package com.neethu.gouxiang.ui.innervoice

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.neethu.gouxiang.data.InnerVoiceEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InnerVoiceDetailTest {
    @get:Rule val compose = createComposeRule()

    private val voice = InnerVoiceEntity(
        id = 1, characterId = 1, title = "测试标题", echo = "这是一段用来测试手动交互的较长心声文字",
        content = "这是完整的心声正文。",
    )

    @Test fun imageEntranceWaitsForAllThreePieces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "inner-voice-test.png")
        file.outputStream().use { output ->
            val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
        }
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent {
                InnerVoiceDetailOverlay(
                    voice = voice.copy(imageFile = file.absolutePath), characterName = "角色", speaking = false,
                    onBack = {}, onSpeak = {}, onStopSpeak = {},
                    onSetBackground = {}, onDownload = { _, _ -> },
                )
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("innerVoiceImageReady"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("innerVoiceSlice2").assertExists()
            compose.onNodeWithContentDescription("朗读心声").assertIsNotEnabled()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("朗读心声").assertIsEnabled()
        } finally {
            compose.mainClock.autoAdvance = true
            file.delete()
        }
    }

    @Test fun controlsRespondAndManualFlipDoesNotRestartAutomaticFlip() {
        val spoken = mutableListOf<String>()
        compose.setContent {
            InnerVoiceDetailOverlay(
                voice = voice, characterName = "角色", speaking = false,
                onBack = {}, onSpeak = { spoken.add(it) }, onStopSpeak = {},
                onSetBackground = {}, onDownload = { _, _ -> },
            )
        }
        compose.onNodeWithText("重新生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("朗读心声").performClick()
        compose.runOnIdle { assertEquals("测试标题。这是完整的心声正文。", spoken.last()) }
        compose.onNodeWithText("查看心声").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("这是完整的心声正文。"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("这是完整的心声正文。").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("查看心声"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Wait longer than the typewriter duration; returning to the front must stay there.
        Thread.sleep(3_500)
        compose.onNodeWithText("查看心声").assertIsDisplayed()
    }

    @Test fun typewriterCompletionAutomaticallyShowsAndReadsLetter() {
        val spoken = mutableListOf<String>()
        compose.setContent {
            InnerVoiceDetailOverlay(
                voice = voice.copy(echo = "你好"), characterName = "角色", speaking = false,
                onBack = {}, onSpeak = { spoken.add(it) }, onStopSpeak = {},
                onSetBackground = {}, onDownload = { _, _ -> },
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("这是完整的心声正文。"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("这是完整的心声正文。").assertIsDisplayed()
        compose.runOnIdle { assertEquals("测试标题。这是完整的心声正文。", spoken.last()) }
    }
}
