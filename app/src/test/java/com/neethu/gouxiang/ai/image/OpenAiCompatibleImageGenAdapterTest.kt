package com.neethu.gouxiang.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 生图响应兼容 b64_json(07 文档 §3.3 要求 b64_json/url 双响应形态)单测:
 * parseUrls 的 data[]/images[] 两形态 × url/b64_json 字段组合,以及
 * AvatarImageProvider 对 data: 伪 URL 的本地解码落盘。
 * ImageGenAdapter / AvatarImageProvider 均零 Android 依赖,纯 JVM 可测。
 */
class OpenAiCompatibleImageGenAdapterTest {

    private val adapter = OpenAiCompatibleImageGenAdapter(
        baseUrl = "https://ark.example.com/api/v3",
        apiKey = "test-key",
        model = "test-model",
    )

    // ── parseUrls:url 双容器形态 + b64_json 兜底 + 坏 JSON ───────────

    @Test
    fun `parseUrls reads url from data array`() {
        val raw = """{"data":[{"url":"https://cdn.example.com/a.png"}]}"""
        assertEquals(listOf("https://cdn.example.com/a.png"), adapter.parseUrls(raw))
    }

    @Test
    fun `parseUrls reads url from images array`() {
        val raw = """{"images":[{"url":"https://cdn.example.com/b.jpg"}]}"""
        assertEquals(listOf("https://cdn.example.com/b.jpg"), adapter.parseUrls(raw))
    }

    @Test
    fun `parseUrls wraps b64_json as data url when url missing`() {
        val payload = Base64.getEncoder().encodeToString("png-bytes".toByteArray())
        val raw = """{"data":[{"b64_json":"$payload"}]}"""
        assertEquals(listOf("$DATA_URL_PREFIX$payload"), adapter.parseUrls(raw))
    }

    @Test
    fun `parseUrls prefers url over b64_json in same element`() {
        val payload = Base64.getEncoder().encodeToString("ignored".toByteArray())
        val raw = """{"data":[{"url":"https://cdn.example.com/first.png","b64_json":"$payload"}]}"""
        assertEquals(listOf("https://cdn.example.com/first.png"), adapter.parseUrls(raw))
    }

    @Test
    fun `parseUrls handles mixed url and b64_json elements across containers`() {
        val payload = Base64.getEncoder().encodeToString("img".toByteArray())
        val data = """{"data":[{"url":"https://cdn.example.com/x.png"},{"b64_json":"$payload"}]}"""
        val images = """{"images":[{"b64_json":"$payload"},{"url":"https://cdn.example.com/y.jpg"}]}"""
        assertEquals(
            listOf("https://cdn.example.com/x.png", "$DATA_URL_PREFIX$payload"),
            adapter.parseUrls(data),
        )
        assertEquals(
            listOf("$DATA_URL_PREFIX$payload", "https://cdn.example.com/y.jpg"),
            adapter.parseUrls(images),
        )
    }

    @Test
    fun `parseUrls returns empty list on broken json`() {
        assertEquals(emptyList<String>(), adapter.parseUrls("""{"data":[{"url":"unterminated"""))
    }

    @Test
    fun `parseUrls returns empty list when neither url nor b64_json present`() {
        assertEquals(emptyList<String>(), adapter.parseUrls("""{"data":[{"revised_prompt":"a cat"}]}"""))
    }

    @Test
    fun `request body keeps url response format`() {
        // ③ 不改请求体:response_format="url" 保留,b64 分支只是响应侧兼容兜底
        val body = adapter.buildRequestBody(ImageGenRequest(prompt = "cat", size = "1024x1024"))
        assertTrue(body.contains("\"response_format\":\"url\""))
    }

    // ── AvatarImageProvider:data: 伪 URL 直接本地解码落盘 ─────────────

    private fun tempFilesDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    @Test
    fun `data url is decoded to file without http`() {
        val payload = Base64.getEncoder().encodeToString("fake-jpeg-content".toByteArray())
        val expected = Base64.getDecoder().decode(payload)
        val filesDir = tempFilesDir("avatar-test")
        try {
            val provider = AvatarImageProvider(filesDir = filesDir, arkApiKey = "", siliconflowApiKey = "")
            val target = provider.fileFor("sparky")

            val decoded = provider.decodeDataUrl("$DATA_URL_PREFIX$payload", target)

            assertNotNull("data: URL 应解码成功并落盘", decoded)
            assertEquals(target, decoded)
            assertEquals(expected.toList(), decoded!!.readBytes().toList())
            assertTrue("落盘文件应非空", decoded.length() > 0)
            assertFalse("不应残留 .tmp 文件", File(decoded.parentFile, decoded.name + ".tmp").exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `malformed data url without payload returns null`() {
        val filesDir = tempFilesDir("avatar-test-bad")
        try {
            val provider = AvatarImageProvider(filesDir = filesDir, arkApiKey = "", siliconflowApiKey = "")
            val target = provider.fileFor("sparky")

            val decoded = provider.decodeDataUrl("data:image/png;base64,", target)

            assertNull("空 payload 应判为失败", decoded)
            assertFalse("失败不应生成目标文件", target.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `invalid base64 data url returns null without writing file`() {
        val filesDir = tempFilesDir("avatar-test-invalid")
        try {
            val provider = AvatarImageProvider(filesDir = filesDir, arkApiKey = "", siliconflowApiKey = "")
            val target = provider.fileFor("sparky")

            val decoded = provider.decodeDataUrl("$DATA_URL_PREFIX@@@not-base64@@@", target)

            assertNull("坏 base64 应判为失败", decoded)
            assertFalse("失败不应生成目标文件", target.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
