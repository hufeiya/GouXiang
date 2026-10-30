package com.neethu.gouxiang.chat

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivativeFailureTest {
    @Test
    fun `classifies without exposing provider response`() {
        val auth = DerivativeFailure.from(IllegalStateException("LLM HTTP 401: secret-response"))
        assertEquals("模型鉴权失败，请检查 API 配置", auth.reason)
        assertFalse(auth.retryable)
        assertFalse(auth.reason.contains("secret-response"))
        assertTrue(DerivativeFailure.from(IOException("private" )).retryable)
        assertTrue(DerivativeFailure.from(IllegalStateException("LLM HTTP 503: private")).retryable)
        assertFalse(DerivativeFailure.format().retryable)
        assertEquals("模型返回空内容，请重试", DerivativeFailure.empty().reason)
    }
}
