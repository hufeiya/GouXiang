package com.neethu.gouxiang.ui

import com.neethu.gouxiang.data.DerivativeResultEntity

/** Status of the image associated with a generated card or post. */
data class ImageGenerationState(val requestId: String, val status: String, val conversationId: Long)

internal fun imageStates(results: List<DerivativeResultEntity>): Map<Long, ImageGenerationState> =
    buildMap {
        results.forEach { result ->
            result.resultIds.split(',').mapNotNull(String::toLongOrNull).forEach { id ->
                putIfAbsent(id, ImageGenerationState(result.requestId, result.imageStatus, result.sourceConversationId))
            }
        }
    }
