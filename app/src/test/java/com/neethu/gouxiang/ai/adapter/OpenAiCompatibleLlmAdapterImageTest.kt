package com.neethu.gouxiang.ai.adapter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleLlmAdapterImageTest {
    @Test
    fun `image data reaches chat completions as multimodal user content`() = runBlocking {
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requestBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("data: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val adapter = OpenAiCompatibleLlmAdapter("https://example.test/v1", "key", client)
        val dataUrl = "data:image/jpeg;base64,aW1hZ2UtYnl0ZXM="
        val events = adapter.streamChat(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "system"),
                ChatMessage(ChatRole.USER, "看这张图片", images = listOf(dataUrl)),
            ),
            LlmConfig("https://example.test/v1", "key", "vision-model"),
        ).toList()

        assertTrue(events.any { it is LlmStreamEvent.Finish })
        val messages = Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val parts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看这张图片", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(dataUrl, parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
}
