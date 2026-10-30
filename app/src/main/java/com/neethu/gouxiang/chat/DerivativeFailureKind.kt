package com.neethu.gouxiang.chat

/**
 * 衍生任务失败原因分类(M0 收尾工作包 1;路线图 §3.6「失败与降级」):
 * 固定六类,持久化到 `derivative_tasks.failureKind`(存 [name]),任务历史页
 * 按 [label] 展示。纯 Kotlin 零 Android 依赖,分类规则 JVM 单测锁死。
 *
 * 分类边界:
 * - [TIMEOUT] 只收「明确超时」:SocketTimeoutException、HTTP 408、流首 delta 超时;
 * - [NETWORK] 收其余 IO/连接层失败(含 5xx/429「服务不可用」——同一网络层语义,
 *   可重试);没有密钥或配置问题不会落在这两类;
 * - [PARSE] 收模型输出为空/格式无法解析(LLM 输出未经 schema 校验不落库的失败面);
 * - [USER_CANCEL] 只收用户/系统主动取消(CancellationException、任务页手动取消);
 * - 其余一律 [UNKNOWN],不猜。
 */
enum class DerivativeFailureKind(val label: String) {
    NETWORK("网络失败"),
    AUTH("鉴权失败"),
    TIMEOUT("超时"),
    PARSE("解析失败"),
    USER_CANCEL("用户取消"),
    UNKNOWN("未知"),
    ;

    companion object {

        /** 存库字符串 → 枚举(未知/历史空值一律回落 [UNKNOWN],不抛)。 */
        fun fromName(value: String?): DerivativeFailureKind =
            entries.firstOrNull { it.name == value } ?: UNKNOWN

        /**
         * 异常 → 分类(纯函数;正则只读固定前缀 `LLM HTTP <code>`,不透出
         * 响应内容)。调用方保证不在这里泄漏密钥或完整私人聊天。
         */
        fun from(error: Throwable): DerivativeFailureKind = when (error) {
            is DerivativeFailure -> error.kind
            is kotlinx.coroutines.CancellationException -> USER_CANCEL
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.io.IOException -> NETWORK
            else -> httpStatus(error.message.orEmpty()).let { status ->
                when (status) {
                    401, 403 -> AUTH
                    408 -> TIMEOUT
                    429, in 500..599 -> NETWORK
                    else -> UNKNOWN
                }
            }
        }

        /** `LLM HTTP <code>` 前缀 → 状态码(与 [DerivativeFailure.from] 同一口径)。 */
        fun httpStatus(message: String): Int? =
            Regex("^LLM HTTP (\\d{3})(?::|$)").find(message)?.groupValues?.get(1)?.toIntOrNull()
    }
}
