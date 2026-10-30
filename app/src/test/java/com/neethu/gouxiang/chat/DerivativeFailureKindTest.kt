package com.neethu.gouxiang.chat

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败原因分类单测(M0 收尾工作包 1):六类固定(网络失败/鉴权失败/超时/解析
 * 失败/用户取消/未知),分类规则纯函数锁死;失败文案不含上游响应内容。
 */
class DerivativeFailureKindTest {

    @Test
    fun `io failures are network and socket timeouts are timeout`() {
        assertEquals(DerivativeFailureKind.NETWORK, DerivativeFailureKind.from(IOException("upstream body")))
        assertEquals(DerivativeFailureKind.TIMEOUT, DerivativeFailureKind.from(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `http status drives auth timeout and server failures`() {
        assertEquals(DerivativeFailureKind.AUTH, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 401: secret-body")))
        assertEquals(DerivativeFailureKind.AUTH, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 403: secret-body")))
        assertEquals(DerivativeFailureKind.TIMEOUT, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 408: slow")))
        assertEquals(DerivativeFailureKind.NETWORK, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 503: down")))
        assertEquals(DerivativeFailureKind.NETWORK, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 429: rate")))
        assertEquals(DerivativeFailureKind.UNKNOWN, DerivativeFailureKind.from(IllegalStateException("LLM HTTP 418: teapot")))
        assertEquals(DerivativeFailureKind.UNKNOWN, DerivativeFailureKind.from(IllegalStateException("plain crash")))
    }

    @Test
    fun `cancellation is user cancel and parse failures are parse`() {
        assertEquals(DerivativeFailureKind.USER_CANCEL, DerivativeFailureKind.from(CancellationException("stopped")))
        assertEquals(DerivativeFailureKind.PARSE, DerivativeFailureKind.from(DerivativeFailure.format()))
        assertEquals(DerivativeFailureKind.PARSE, DerivativeFailureKind.from(DerivativeFailure.empty()))
    }

    @Test
    fun `labels are the fixed six categories`() {
        assertEquals(
            listOf("网络失败", "鉴权失败", "超时", "解析失败", "用户取消", "未知"),
            DerivativeFailureKind.entries.map { it.label },
        )
    }

    @Test
    fun `fromName falls back to unknown instead of throwing`() {
        assertEquals(DerivativeFailureKind.UNKNOWN, DerivativeFailureKind.fromName(null))
        assertEquals(DerivativeFailureKind.UNKNOWN, DerivativeFailureKind.fromName("historical-garbage"))
        assertEquals(DerivativeFailureKind.AUTH, DerivativeFailureKind.fromName("AUTH"))
    }

    @Test
    fun `existing reasons and retryability are unchanged`() {
        val auth = DerivativeFailure.from(IllegalStateException("LLM HTTP 401: secret-response"))
        assertEquals("模型鉴权失败，请检查 API 配置", auth.reason)
        assertFalse(auth.retryable)
        assertFalse(auth.reason.contains("secret-response"))
        assertTrue(DerivativeFailure.from(IOException("private")).retryable)
        assertTrue(DerivativeFailure.from(IllegalStateException("LLM HTTP 503: private")).retryable)
        assertEquals("模型服务暂时不可用，请稍后重试", DerivativeFailure.from(IllegalStateException("LLM HTTP 503: x")).reason)
        assertEquals("网络连接失败，请检查网络后重试", DerivativeFailure.from(IOException("x")).reason)
        assertFalse(DerivativeFailure.format().retryable)
        assertEquals("模型返回空内容，请重试", DerivativeFailure.empty().reason)
    }
}
