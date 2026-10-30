package com.neethu.gouxiang.chat

/** Only fixed, non-sensitive messages leave the generation boundary. */
internal class DerivativeFailure(val reason: String, val retryable: Boolean) : Exception(reason) {
    companion object {
        fun from(error: Throwable): DerivativeFailure {
            if (error is DerivativeFailure) return error
            if (error is java.io.IOException) return DerivativeFailure("网络连接失败，请检查网络后重试", true)
            val status = Regex("^LLM HTTP (\\d{3})(?::|$)").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            return when (status) {
                401, 403 -> DerivativeFailure("模型鉴权失败，请检查 API 配置", false)
                408, 429, in 500..599 -> DerivativeFailure("模型服务暂时不可用，请稍后重试", true)
                else -> DerivativeFailure("模型请求失败，请检查配置后重试", false)
            }
        }
        fun empty() = DerivativeFailure("模型返回空内容，请重试", false)
        fun format() = DerivativeFailure("模型返回格式无法解析，请重试", false)
    }
}
