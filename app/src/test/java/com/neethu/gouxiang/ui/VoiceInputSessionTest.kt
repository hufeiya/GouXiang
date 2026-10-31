package com.neethu.gouxiang.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceInputSessionTest {
    @Test
    fun pendingRecognitionDoesNotSendAfterSwitchingChats() = runTest {
        val session = VoiceInputSession()
        val token = session.begin(12L)
        val recognition = CompletableDeferred<String>()
        var currentChat: Long? = 12L
        val delivered = mutableListOf<String>()
        val job = async {
            val text = recognition.await()
            if (session.isCurrent(token, currentChat)) delivered += text
        }
        runCurrent()
        session.invalidate()
        currentChat = 13L
        recognition.complete("你好")
        job.await()
        assertEquals(emptyList<String>(), delivered)
    }

    @Test
    fun transcriptionBelongsToTheChatWhereRecordingStarted() {
        val session = VoiceInputSession()
        val token = session.begin(12L)

        assertTrue(session.isCurrent(token, 12L))
        assertFalse(session.isCurrent(token, 13L))
        assertFalse(session.isCurrent(token, null))
    }

    @Test
    fun leavingAndReenteringTheSameChatDoesNotDeliverOldTranscription() {
        val session = VoiceInputSession()
        val token = session.begin(12L)
        session.invalidate()

        assertFalse(session.isCurrent(token, 12L))
        val newToken = session.begin(12L)
        assertTrue(session.isCurrent(newToken, 12L))
        assertFalse(session.isCurrent(token, 12L))
    }
}
