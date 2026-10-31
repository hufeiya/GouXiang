package com.neethu.gouxiang.ui

/** Prevents a transcription from being delivered after its chat session has changed. */
internal class VoiceInputSession {
    data class Token(val generation: Long, val conversationId: Long)

    private var generation = 0L

    fun begin(conversationId: Long): Token = Token(++generation, conversationId)

    fun invalidate() { generation++ }

    fun isCurrent(token: Token, conversationId: Long?): Boolean =
        token.generation == generation && token.conversationId == conversationId
}
