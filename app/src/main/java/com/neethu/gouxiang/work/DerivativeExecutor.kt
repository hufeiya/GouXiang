package com.neethu.gouxiang.work

import android.content.Context
import android.util.Log
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.InnerVoiceChannel
import com.neethu.gouxiang.chat.InnerVoiceGenerator
import com.neethu.gouxiang.chat.MomentsChannel
import com.neethu.gouxiang.chat.MomentsGenerator

/**
 * 衍生任务执行器(docs/background-generation-plan-20261030.md §2.2 核心分发器)。
 *
 * `execute(app, kind, conversationId): Boolean` 逐分支复用现有 LLM 链路
 * (InnerVoiceChannel/MomentsChannel 是独立小类,Worker 直接实例化),不新增
 * 抽象层。返回 false → Worker Result.retry(网络抖动靠 WorkManager 退避)。
 *
 * **开关门控(§3.4)**:自动路径入队前(Scheduler)与执行时(本处二次自查)
 * 双闸防竞态;点击路径不受开关限制(调用方传 auto=false 直入 execute)。
 */
internal class DerivativeExecutor(private val app: Context) {

    /** @return true=完成落库;false=失败应重试。 */
    suspend fun execute(kind: DerivativeKind, conversationId: Long): Boolean =
        when (kind) {
            DerivativeKind.INNER_VOICE -> executeInnerVoice(conversationId)
            DerivativeKind.MOMENT -> executeMoment(conversationId)
            DerivativeKind.MEMORY_SUMMARY -> executeMemorySummary(conversationId)
            DerivativeKind.PROACTIVE_OUTBOX -> executeProactiveOutbox()
            // M2 接入前不触达(枚举先行,Scheduler/点击均不派发这些 kind;
            // 触达即视为无操作的完成,不烧请求不重试)。
            DerivativeKind.HANDBOOK, DerivativeKind.NOVEL, DerivativeKind.PHOTO -> true
        }

    // ── 心声(F8 主体搬运;判空/失败语义与 UI 版一致)────────────────

    private suspend fun executeInnerVoice(conversationId: Long): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conv = repo.db.conversationDao().byId(conversationId) ?: return true // 会话已删,任务完成
        val character = repo.characterById(conv.characterId) ?: return true
        val memories = repo.memoriesFor(character.id)
        val recent = repo.recentMessages(conversationId, ChatOrchestrator.HISTORY_LIMIT)
        val system = InnerVoiceGenerator.systemPrompt(character, memories)
        val user = InnerVoiceGenerator.userPrompt(character, toMemoryTurns(recent))
        val raw = innerVoiceChannel.request(system, user)
        val entities = raw?.let { InnerVoiceGenerator.toEntities(it, character.id, System.currentTimeMillis()) }
        if (entities.isNullOrEmpty()) {
            Log.w(TAG, "inner voice generate failed for conv=$conversationId, retryable")
            return false
        }
        val inserted = repo.insertInnerVoices(entities)
        // 配图异步回填(纯文字卡已可上屏;生图失败保持纯文字卡,与 UI 版同语义)
        val provider = imageProvider()
        inserted.forEachIndexed { index, id ->
            if (id <= 0L) return@forEachIndexed
            val entity = entities[index]
            val prompt = buildString {
                append(character.appearance)
                append("。情绪:${entity.title},${entity.echo}。")
                append(character.appearanceStyle)
                append(",柔和电影感光影,情绪特写,竖版构图")
            }
            val file = runCatching {
                provider.getOrGenerateInnerVoiceImage(characterKey = "inner-voice-$id", prompt = prompt)
            }.getOrNull()
            if (file != null) repo.setInnerVoiceImage(id, file.absolutePath)
        }
        NotificationPoster.derivative(app, DerivativeKind.INNER_VOICE, character.name, conversationId)
        return true
    }

    // ── 朋友圈(F9 主体搬运;防重复发帖按当日查重)────────────────────

    private suspend fun executeMoment(conversationId: Long): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conv = repo.db.conversationDao().byId(conversationId) ?: return true
        val character = repo.characterById(conv.characterId) ?: return true
        // 幂等:今日已发过一条 → 任务完成不重发(§2.2 朋友圈防重复)
        val dayStart = todayStartMs()
        if (repo.moments(character.id).any { it.at >= dayStart }) return true
        val memories = repo.memoriesFor(character.id)
        val recent = repo.recentMessages(conversationId, ChatOrchestrator.HISTORY_LIMIT)
        val system = MomentsGenerator.postSystemPrompt(character, memories)
        val user = MomentsGenerator.postUserPrompt(character, toMemoryTurns(recent))
        val raw = momentsChannel.requestPost(system, user)
        val draft = raw?.let { MomentsGenerator.parsePost(it) }
        if (draft == null) {
            Log.w(TAG, "moment generate failed for conv=$conversationId, retryable")
            return false
        }
        val postId = repo.insertMoment(MomentsGenerator.toEntity(draft, character.id, System.currentTimeMillis()))
        if (draft.imagePrompt.isNotBlank() && postId > 0L) {
            val prompt = buildString {
                append(character.appearanceStyle)
                append(",")
                append(draft.imagePrompt)
                append(",生活随拍,柔和自然光")
            }
            val file = runCatching {
                imageProvider().getOrGenerateMomentImage(characterKey = "moment-$postId", prompt = prompt)
            }.getOrNull()
            if (file != null) repo.setMomentImage(postId, file.absolutePath)
        }
        NotificationPoster.derivative(app, DerivativeKind.MOMENT, character.name, conversationId)
        return true
    }

    // ── 隔夜记忆总结兜底(A16;§4 M1.5)──────────────────────────────

    /**
     * 窗口非空 + 会话闲置 ≥8h → 触发与 [ChatOrchestrator memoryHook] 同款总结
     * (独立组装协调器,不经主 VM 的viewModelScope——进程死也不丢窗口持久化)。
     */
    private suspend fun executeMemorySummary(conversationId: Long): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conv = repo.db.conversationDao().byId(conversationId) ?: return true
        val character = repo.characterById(conv.characterId) ?: return true
        val store = com.neethu.gouxiang.data.RoomMemoryWindowStore(repo)
        val payload = store.loadPayload()
        val turns = payload?.let { com.neethu.gouxiang.data.deserializeWindow(it) }.orEmpty()
        if (turns.isEmpty()) return true // 窗口空 = 无待提炼内容,完成
        val idleMs = System.currentTimeMillis() - conv.lastAt
        if (idleMs < MEMORY_OVERNIGHT_IDLE_MS) {
            Log.i(TAG, "conversation not idle enough ($idleMs < $MEMORY_OVERNIGHT_IDLE_MS), defer")
            return false // 未达标 → retry,WorkManager 退避后再次自查
        }
        val book = com.neethu.gouxiang.chat.MemoryBook(com.neethu.gouxiang.data.RoomMemorySource(repo)).also {
            it.characterNameResolver = { id -> repo.characterById(id)?.name.orEmpty() }
        }
        val extractor = com.neethu.gouxiang.chat.MemoryExtractor(
            book = book,
            llm = ChatOrchestrator.defaultLlm(),
            llmConfig = {
                val e = com.neethu.gouxiang.ai.config.AiConfigStore.load(app).llmEndpoint()
                com.neethu.gouxiang.ai.adapter.LlmConfig(
                    baseUrl = e.baseUrl, apiKey = e.apiKey, model = e.model,
                    temperature = 0.2f, topP = 0.9f, maxTokens = 512, extraBody = e.extraBody,
                )
            },
        )
        // 直连总结通道(不经协调器窗口快照——窗口由本任务消费后清空)
        val ok = runCatching { extractor.summarize(turns, character) }
            .onFailure { Log.w(TAG, "overnight summarize failed: ${it.message}") }
            .getOrDefault(false)
        if (!ok) return false
        // 成功即清窗口(与 ChatMemoryCoordinator.summarizeNow 成功路径同语义)
        store.save("")
        return true
    }

    // ── 离线主动消息 outbox(B9;§4 M1.2)────────────────────────────

    /**
     * 扫全部会话 `pickTrigger`,命中的会话直接生成主动消息落库(与进页判定
     * 共用 orchestrator.respondProactively 链路;生成落库即上屏),后发通道二
     * 通知。生成失败(单会话)继续扫其余会话,整体返回 false 触发 retry。
     */
    private suspend fun executeProactiveOutbox(): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conversations = repo.db.conversationDao().latest(50)
        var hitCount = 0
        var anyFailed = false
        var firstHitConvId = 0L
        var firstHitName = ""
        for (conv in conversations) {
            val character = repo.characterById(conv.characterId) ?: continue
            val msgs = repo.allMessages(conv.id)
            val everProactive = msgs.any { it.turnId.startsWith("proactive-") }
            val kind = com.neethu.gouxiang.protocol.ProactiveMessages.pickTrigger(
                everProactive = everProactive,
                lastActivityAt = conv.lastAt,
                now = System.currentTimeMillis(),
            ) ?: continue
            val ok = runCatching {
                // 手机聊天形态会话按手机 burst 语义生成;IM 会话同理。
                val phone = conv.mode == "phone"
                val burst = com.neethu.gouxiang.chat.BurstParams.resolve(
                    com.neethu.gouxiang.chat.ReplyLength.MEDIUM, phone, com.neethu.gouxiang.chat.DialogMode.DEFAULT,
                )
                com.neethu.gouxiang.chat.ChatContextBuilder.withPhoneStyle(phone) {
                    buildOutboxOrchestrator().respondProactively(
                        conversationId = conv.id,
                        characterId = character.id,
                        sceneLine = com.neethu.gouxiang.protocol.ProactiveMessages.sceneLine(
                            kind, System.currentTimeMillis() - conv.lastAt,
                        ),
                        onAssistantSaved = { },
                        burst = burst,
                        mode = com.neethu.gouxiang.chat.DialogMode.DEFAULT,
                    )
                }
            }.onFailure { Log.w(TAG, "outbox round failed conv=${conv.id}: ${it.message}") }
                .isSuccess
            if (ok) {
                hitCount++
                if (firstHitConvId == 0L) {
                    firstHitConvId = conv.id
                    firstHitName = character.name
                }
            } else anyFailed = true
        }
        if (hitCount > 0 && firstHitName.isNotBlank()) {
            NotificationPoster.proactive(app, firstHitName, firstHitConvId, hitCount)
        }
        return !anyFailed
    }

    /** outbox 会话的编排器实例(每会话独立 LLM 配置现取;与 UI 通道同缝)。 */
    private fun buildOutboxOrchestrator() = ChatOrchestrator(
        com.neethu.gouxiang.data.ChatDaoAccess(com.neethu.gouxiang.data.GouXiangRepo.get(app)),
        llm = ChatOrchestrator.defaultLlm(),
        llmEndpointSource = {
            com.neethu.gouxiang.ai.config.AiConfigStore.load(app).llmEndpoint()
        },
        bubbleGapMs = 0L, // 后台无 UI 连发节奏,直接落库
    )

    // ── 通道(与 VM 同款懒单例)──────────────────────────────────────

    private val innerVoiceChannelLazy by lazy {
        InnerVoiceChannel(
            llm = ChatOrchestrator.defaultLlm(),
            llmConfig = { com.neethu.gouxiang.work.memoryLlmConfig(app) },
        )
    }
    private val innerVoiceChannel: InnerVoiceChannel get() = innerVoiceChannelLazy

    private val momentsChannelLazy by lazy {
        MomentsChannel(
            llm = ChatOrchestrator.defaultLlm(),
            llmConfig = { com.neethu.gouxiang.work.memoryLlmConfig(app) },
        )
    }
    private val momentsChannel: MomentsChannel get() = momentsChannelLazy

    private val imageProviderLazy by lazy {
        com.neethu.gouxiang.ai.image.AvatarImageProvider(app.filesDir)
    }
    private fun imageProvider() = imageProviderLazy

    companion object {
        private const val TAG = "DerivativeExecutor"

        /** 隔夜总结的闲置门槛(8h,§2.2 A16 语义)。 */
        const val MEMORY_OVERNIGHT_IDLE_MS: Long = 8L * 60 * 60 * 1000

        /**
         * 消息行 → 提炼窗口回合(与 MainViewModel.toMemoryTurns 同规则;搬为
         * 顶层纯函数供 Worker/VM 共用,测试锁行为)。
         */
        fun toMemoryTurns(messages: List<com.neethu.gouxiang.data.MessageEntity>): List<com.neethu.gouxiang.data.MemoryTurn> {
            val turns = mutableListOf<com.neethu.gouxiang.data.MemoryTurn>()
            var pendingUser: String? = null
            val assistantParts = StringBuilder()
            fun flush() {
                val user = pendingUser
                if (user != null) {
                    turns += com.neethu.gouxiang.data.MemoryTurn(
                        user = user,
                        assistant = assistantParts.toString().trim(),
                    )
                }
                pendingUser = null
                assistantParts.setLength(0)
            }
            for (m in messages) {
                if (m.role == "user") {
                    flush()
                    if (m.content.isNotBlank()) pendingUser = m.content
                } else {
                    val line = com.neethu.gouxiang.chat.ChatContextBuilder.encodeBubbleLine(m)
                    if (line.isNotBlank()) {
                        if (assistantParts.isNotEmpty()) assistantParts.append('\n')
                        assistantParts.append(line)
                    }
                }
            }
            flush()
            return turns
        }

        /** 当日零点 epoch millis(朋友圈每日防重复的查重游标)。 */
        fun todayStartMs(): Long {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

/** 记忆/心声/朋友圈通道的 LLM 配置(Worker 侧现读端点;与 orchestrator.llmConfigForMemory 同参数)。 */
internal fun memoryLlmConfig(app: Context): com.neethu.gouxiang.ai.adapter.LlmConfig {
    val e = com.neethu.gouxiang.ai.config.AiConfigStore.load(app).llmEndpoint()
    return com.neethu.gouxiang.ai.adapter.LlmConfig(
        baseUrl = e.baseUrl,
        apiKey = e.apiKey,
        model = e.model,
        temperature = 0.2f,
        topP = 0.9f,
        maxTokens = 512,
        extraBody = e.extraBody,
    )
}
