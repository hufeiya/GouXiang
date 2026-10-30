package com.neethu.gouxiang.chat

/**
 * Only fixed, non-sensitive messages leave the generation boundary.
 *
 * [kind] 为持久化失败分类(M0 收尾:Worker 落 `derivative_tasks.failureKind`,
 * 任务历史页按分类展示)。既有文案/可重试语义保持不变(单测锁死),只新增分类。
 */
internal class DerivativeFailure(
    val reason: String,
    val retryable: Boolean,
    val kind: DerivativeFailureKind,
) : Exception(reason) {
    companion object {
        fun from(error: Throwable): DerivativeFailure {
            if (error is DerivativeFailure) return error
            val kind = DerivativeFailureKind.from(error)
            val http = DerivativeFailureKind.httpStatus(error.message.orEmpty())
            val (reason, retryable) = when (kind) {
                DerivativeFailureKind.AUTH -> "模型鉴权失败，请检查 API 配置" to false
                DerivativeFailureKind.TIMEOUT -> "模型服务暂时不可用，请稍后重试" to true
                // 429/5xx 是服务端不可用(HTTP 层),IO 才是连接层网络失败;
                // 两者都可重试,文案保持原有口径
                DerivativeFailureKind.NETWORK ->
                    if (http != null) "模型服务暂时不可用，请稍后重试" to true
                    else "网络连接失败，请检查网络后重试" to true
                DerivativeFailureKind.PARSE, DerivativeFailureKind.USER_CANCEL, DerivativeFailureKind.UNKNOWN ->
                    "模型请求失败，请检查配置后重试" to false
            }
            return DerivativeFailure(reason, retryable, kind)
        }
        fun empty() = DerivativeFailure("模型返回空内容，请重试", false, DerivativeFailureKind.PARSE)
        fun format() = DerivativeFailure("模型返回格式无法解析，请重试", false, DerivativeFailureKind.PARSE)
    }
}
