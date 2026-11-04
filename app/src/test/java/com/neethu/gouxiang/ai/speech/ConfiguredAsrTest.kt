package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.AsrProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfiguredAsrTest {
    @Test fun `automatic mode prefers configured local service then falls back to selected cloud`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var localFails = false
        server.createContext("/local/audio/transcriptions") { exchange ->
            requests.add("local:" + exchange.requestBody.readBytes().decodeToString())
            val body = if (localFails) "unavailable" else "{\"text\":\"本地\"}"
            exchange.sendResponseHeaders(if (localFails) 503 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/cloud/audio/transcriptions") { exchange ->
            requests.add("cloud:" + exchange.requestBody.readBytes().decodeToString())
            val body = "{\"text\":\"云端\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val config = AiConfig(provider = AiProvider.CUSTOM, customBaseUrl = "$base/cloud",
                asrLocalBaseUrl = "$base/local", asrLocalModel = "local-model", asrModel = "cloud-model")
            assertEquals("本地", ConfiguredAsr.transcribe(byteArrayOf(1, 2), config))
            assertEquals(1, requests.size)
            assertTrue(requests[0].contains("local-model"))
            localFails = true
            assertEquals("云端", ConfiguredAsr.transcribe(byteArrayOf(1, 2), config))
            assertTrue(requests.last().startsWith("cloud:"))
            assertTrue(requests.last().contains("cloud-model"))
            requests.clear()
            try {
                ConfiguredAsr.transcribe(byteArrayOf(1, 2), config.copy(asrProvider = AsrProvider.LOCAL))
                fail("Explicit local selection must report failure")
            } catch (_: java.io.IOException) {
                assertEquals(1, requests.size)
                assertTrue(requests.single().startsWith("local:"))
            }
            requests.clear()
            assertEquals("云端", ConfiguredAsr.transcribe(byteArrayOf(1, 2), config.copy(asrProvider = AsrProvider.CUSTOM)))
            assertEquals(1, requests.size)
            assertTrue(requests.single().startsWith("cloud:"))
        } finally {
            server.stop(0)
        }
    }
}
