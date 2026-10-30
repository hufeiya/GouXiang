package com.neethu.gouxiang.proactive

import android.content.Context
import com.neethu.gouxiang.ai.config.AiConfigStore
import com.neethu.gouxiang.chat.BurstParams
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DialogMode
import com.neethu.gouxiang.chat.ProactiveTurnResult
import com.neethu.gouxiang.chat.ReplyLength
import com.neethu.gouxiang.data.GouXiangRepo
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.protocol.ProactiveMessages
import com.neethu.gouxiang.data.ChatDaoAccess

/**
 * 主动消息生成的生产装配(D 工作包 D3):把 [ChatOrchestrator.respondProactively]
 * 的回合包装为 [ProactiveOutboxService.GenerationResult] 三态 ——
 * 真实成功 / 模板降级(degraded)→ Ready/Fallback,硬异常与显式 Failed →
 * Failed(调用方记可重试失败)。回归批次上限([batchMax])钳制连发协议,
 * 超出上限的模型输出由解析层截断,不落超批消息。
 */
object ProactiveGeneration {

    fun production(app: Context): suspend (ProactiveOutboxService.GenerationRequest) -> ProactiveOutboxService.GenerationResult =
        { request ->
            val repo = GouXiangRepo.get(app)
            val orchestrator = ChatOrchestrator(
                ChatDaoAccess(repo),
                llm = ChatOrchestrator.defaultLlm(),
                llmEndpointSource = { AiConfigStore.load(app).llmEndpoint() },
                bubbleGapMs = 0L, // 后台/进页补发无 UI 连发节奏,直接落库
            )
            val collected = mutableListOf<MessageEntity>()
            val burst = clampBurst(
                BurstParams.resolve(ReplyLength.MEDIUM, request.phoneStyle, DialogMode.DEFAULT),
                request.batchMax,
            )
            val outcome = orchestrator.respondProactively(
                conversationId = request.conversationId,
                characterId = request.characterId,
                sceneLine = ProactiveMessages.sceneLine(request.kind, request.offlineMs),
                onAssistantSaved = { collected += it },
                burst = burst,
                mode = DialogMode.DEFAULT,
                phoneStyle = request.phoneStyle,
            )
            when (outcome) {
                is ProactiveTurnResult.Completed ->
                    if (outcome.degraded) {
                        ProactiveOutboxService.GenerationResult.Fallback(
                            turnId = collected.firstOrNull()?.turnId.orEmpty(),
                            messages = collected,
                            reason = "模板降级(流失败/空输出的兜底文本)",
                        )
                    } else {
                        ProactiveOutboxService.GenerationResult.Ready(
                            turnId = collected.firstOrNull()?.turnId.orEmpty(),
                            messages = collected,
                        )
                    }
                is ProactiveTurnResult.Failed ->
                    ProactiveOutboxService.GenerationResult.Failed(outcome.reason)
            }
        }

    /** 回归批次上限钳制(纯函数,单测锁死):max 不得超上限,min 不得超 max。 */
    internal fun clampBurst(burst: BurstParams, batchMax: Int): BurstParams {
        val max = burst.maxBubbles.coerceAtMost(batchMax)
        val min = burst.minBubbles.coerceAtMost(max)
        return burst.copy(minBubbles = min, maxBubbles = max)
    }
}
