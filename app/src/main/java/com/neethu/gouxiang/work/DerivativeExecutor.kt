package com.neethu.gouxiang.work

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.neethu.gouxiang.data.DerivativeResultEntity
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import kotlinx.coroutines.CancellationException
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
    suspend fun execute(
        kind: DerivativeKind,
        conversationId: Long,
        task: DerivativeTask = DerivativeTask(kind, conversationId),
        requestId: String = java.util.UUID.randomUUID().toString(),
    ): Boolean =
        when (kind) {
            DerivativeKind.INNER_VOICE -> executeInnerVoice(conversationId, task, requestId)
            DerivativeKind.MOMENT -> executeMoment(conversationId, task, requestId)
            DerivativeKind.MEMORY_SUMMARY -> executeMemorySummary(conversationId)
            DerivativeKind.PROACTIVE_OUTBOX -> executeProactiveOutbox()
            // 番外/心动(F 工作包 F3/F4):按记录粒度生成,幂等/版本拦截/失败
            // 收口都在 SidestoryGenerator 内部(记录行删除=幂等完成,不判失败)。
            DerivativeKind.SIDESTORY -> sidestoryGenerator().generate(task.recordId, requestId)
            // 手帐/小说/合照(G 工作包 G1-G3):按记录粒度生成,幂等/版本拦截/
            // 失败收口都在 ChatDerivativeGenerator 内部(记录行删除=幂等完成)。
            DerivativeKind.HANDBOOK -> chatDerivativeGenerator().generate(task.recordId, requestId)
            DerivativeKind.NOVEL -> chatDerivativeGenerator().generate(task.recordId, requestId)
            DerivativeKind.PHOTO -> chatDerivativeGenerator().generate(task.recordId, requestId)
        }

    /** 番外/心动生成器(生产装配;每次执行现取 repo 单例)。 */
    private fun sidestoryGenerator() =
        com.neethu.gouxiang.sidestory.SidestoryGenerator.production(app)

    /** 手帐/小说/合照生成器(G 工作包;生产装配)。 */
    private fun chatDerivativeGenerator() =
        com.neethu.gouxiang.derivatives.ChatDerivativeGenerator.production(app)

    // ── 心声(F8 主体搬运;判空/失败语义与 UI 版一致)────────────────

    internal suspend fun executeInnerVoice(conversationId: Long, task: DerivativeTask, requestId: String, candidate: com.neethu.gouxiang.data.ContentCandidateEntity? = null): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conv = repo.db.conversationDao().byId(conversationId) ?: return false
        val character = repo.characterById(conv.characterId) ?: return false
        if (task.characterId > 0 && task.characterId != character.id) return false
        var result = repo.db.derivativeResultDao().byRequestId(requestId)
        if (task.imageRetryRequestId != null && result == null) return false
        if (result == null) {
            val source = if (candidate == null) captureDerivativeSource(repo.db, conversationId, character.id)
                else captureCandidateSource(repo.db, candidate)
            val memories = source.memories
            val recent = source.messages
            val raw = innerVoiceChannel.request(
                InnerVoiceGenerator.systemPrompt(character, memories) + if (candidate != null) "\n只写一条心声，标题固定为：${candidate.title}。" else "",
                InnerVoiceGenerator.userPrompt(character, toMemoryTurns(recent)),
                strict = true,
            )
            val entities = if (candidate == null) {
                raw?.let { InnerVoiceGenerator.toEntities(it, character.id, System.currentTimeMillis()) }
            } else {
                raw?.let { InnerVoiceGenerator.parse(it).firstOrNull() }?.let { draft ->
                    listOf(com.neethu.gouxiang.data.InnerVoiceEntity(characterId = character.id, title = candidate.title,
                        echo = draft.echo, content = draft.content, at = System.currentTimeMillis()))
                }
            }
            if (entities.isNullOrEmpty()) throw com.neethu.gouxiang.chat.DerivativeFailure.format()
            result = repo.db.withTransaction {
                repo.db.derivativeResultDao().byRequestId(requestId) ?: run {
                    requireCurrentDerivativeSource(repo.db, conversationId, source.version)
                    if (candidate != null) requireCurrentCandidate(repo.db, candidate)
                    val ids = repo.insertInnerVoices(entities)
                    DerivativeResultEntity(requestId, DerivativeKind.INNER_VOICE.name, character.id, ids.joinToString(","),
                        imageStatus = if (GenerationPrefs.innerVoiceImages(app)) "pending" else "skipped",
                        sourceConversationId = conversationId, sourceStartMessageId = source.firstMessageId,
                        sourceEndMessageId = source.lastMessageId, sourceVersion = source.version)
                        .also { repo.db.derivativeResultDao().insert(it) }
                }
            }
        }
        var savedResult = result ?: return false
        if (task.imageRetryRequestId != null && savedResult.imageStatus in setOf("failed", "skipped")) {
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, "pending")
            }
            savedResult = savedResult.copy(imageStatus = "pending")
        } else if (task.imageRetryRequestId == null && savedResult.imageStatus == "pending" && !GenerationPrefs.innerVoiceImages(app)) {
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, "skipped")
            }
            savedResult = savedResult.copy(imageStatus = "skipped")
        }
        repo.db.withTransaction { requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion) }
        if (savedResult.imageStatus == "pending") {
            val ids = savedResult.resultIds.split(',').mapNotNull { it.toLongOrNull() }.toSet()
            val voices = repo.innerVoices(character.id).filter { it.id in ids }
            var imageFailed = false
            for (voice in voices) {
                if (voice.imageFile.isNotBlank()) continue
                // 参照人物封面形象生成(图生图):封面存在则作参考图;缺失回落纯文生图
                val coverFile = character.avatarFile.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(java.io.File(app.filesDir, "avatars"), it) }
                val prompt = "${character.appearance}。保持参考图中人物的样貌、发型与穿搭," +
                    "画面情绪:${voice.title},${(voice.content.ifBlank { voice.echo }).take(80)}。" +
                    "${character.appearanceStyle},柔和电影感光影,情绪特写,竖版构图"
                val file = try {
                    imageProvider().getOrGenerateInnerVoiceImage(
                        characterKey = "inner-voice-${voice.id}", prompt = prompt, referenceImage = coverFile,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (file == null) imageFailed = true else repo.db.withTransaction {
                    requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                    repo.setInnerVoiceImage(voice.id, file.absolutePath)
                }
            }
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, if (imageFailed) "failed" else "complete")
            }
        }
        postDerivativeNotification(savedResult, DerivativeKind.INNER_VOICE, character.name)
        return true
    }

    internal suspend fun executeMoment(conversationId: Long, task: DerivativeTask, requestId: String, candidate: com.neethu.gouxiang.data.ContentCandidateEntity? = null): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val conv = repo.db.conversationDao().byId(conversationId) ?: return false
        val character = repo.characterById(conv.characterId) ?: return false
        if (task.characterId > 0 && task.characterId != character.id) return false
        var result = repo.db.derivativeResultDao().byRequestId(requestId)
        if (task.imageRetryRequestId != null && result == null) return false
        if (result == null) {
            val source = if (candidate == null) captureDerivativeSource(repo.db, conversationId, character.id)
                else captureCandidateSource(repo.db, candidate)
            val memories = source.memories
            val recent = source.messages
            val raw = momentsChannel.requestPost(
                MomentsGenerator.postSystemPrompt(character, memories),
                MomentsGenerator.postUserPrompt(character, toMemoryTurns(recent)) +
                    if (candidate != null) "\n请围绕这一段对话中的主题：${candidate.title}" else "",
                strict = true,
            )
            val draft = raw?.let { MomentsGenerator.parsePost(it) } ?: throw com.neethu.gouxiang.chat.DerivativeFailure.format()
            result = repo.db.withTransaction {
                repo.db.derivativeResultDao().byRequestId(requestId) ?: run {
                    requireCurrentDerivativeSource(repo.db, conversationId, source.version)
                    if (candidate != null) requireCurrentCandidate(repo.db, candidate)
                    val id = repo.insertMoment(MomentsGenerator.toEntity(draft, character.id, System.currentTimeMillis()))
                    DerivativeResultEntity(requestId, DerivativeKind.MOMENT.name, character.id, id.toString(),
                        imageStatus = if (GenerationPrefs.momentsImages(app)) "pending" else "skipped",
                        sourceConversationId = conversationId, sourceStartMessageId = source.firstMessageId,
                        sourceEndMessageId = source.lastMessageId, sourceVersion = source.version)
                        .also { repo.db.derivativeResultDao().insert(it) }
                }
            }
        }
        var savedResult = result ?: return false
        if (task.imageRetryRequestId != null && savedResult.imageStatus in setOf("failed", "skipped")) {
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, "pending")
            }
            savedResult = savedResult.copy(imageStatus = "pending")
        } else if (task.imageRetryRequestId == null && savedResult.imageStatus == "pending" && !GenerationPrefs.momentsImages(app)) {
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, "skipped")
            }
            savedResult = savedResult.copy(imageStatus = "skipped")
        }
        repo.db.withTransaction { requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion) }
        if (savedResult.imageStatus == "pending") {
            val post = savedResult.resultIds.toLongOrNull()?.let { repo.db.momentDao().byId(it) }
            var imageFailed = false
            if (post != null && post.imagePrompt.isNotBlank() && post.imageFile.isBlank()) {
                val prompt = "${character.appearanceStyle},${post.imagePrompt},生活随拍,柔和自然光"
                val file = try {
                    imageProvider().getOrGenerateMomentImage(characterKey = "moment-${post.id}", prompt = prompt)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (file == null) imageFailed = true else repo.db.withTransaction {
                    requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                    repo.setMomentImage(post.id, file.absolutePath)
                }
            }
            repo.db.withTransaction {
                requireCurrentDerivativeSource(repo.db, savedResult.sourceConversationId, savedResult.sourceVersion)
                repo.db.derivativeResultDao().setImageStatus(requestId, if (imageFailed) "failed" else "complete")
            }
        }
        postDerivativeNotification(savedResult, DerivativeKind.MOMENT, character.name)
        runMomentInteractions(repo, savedResult.resultIds.toLongOrNull())
        return true
    }

    /**
     * 角色帖的一轮互评(H3):动态落库后由其他授权角色各评一条;互评失败
     * 只释放认领(引擎内兜底),不影响动态任务本身的成功(帖子已真实落库,
     * 不能因互动失败回拨任务态)。
     */
    private suspend fun runMomentInteractions(repo: com.neethu.gouxiang.data.GouXiangRepo, postId: Long?) {
        val post = postId?.let { repo.db.momentDao().byId(it) } ?: return
        runCatching { com.neethu.gouxiang.chat.MomentInteractionEngine(repo.db, momentsChannel).runRound(post) }
            .onFailure { Log.w(TAG, "moment interactions failed post=$postId", it) }
    }

    private suspend fun postDerivativeNotification(result: DerivativeResultEntity, kind: DerivativeKind, name: String) {
        val db = com.neethu.gouxiang.data.GouXiangRepo.get(app).db
        // Notifications use a stable ID, so a crash between posting and recording it replaces
        // the same notification on retry. Successful scans do not post it repeatedly.
        db.withTransaction {
            requireCurrentDerivativeSource(db, result.sourceConversationId, result.sourceVersion)
            val current = db.derivativeResultDao().byRequestId(result.requestId) ?: return@withTransaction
            if (!current.notificationDone) {
                NotificationPoster.derivative(app, kind, name, result.sourceConversationId)
                db.derivativeResultDao().markNotificationDone(result.requestId)
            }
        }
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
        val idleMs = System.currentTimeMillis() - conv.lastAt
        if (idleMs < MEMORY_OVERNIGHT_IDLE_MS) {
            Log.i(TAG, "conversation not idle enough ($idleMs < $MEMORY_OVERNIGHT_IDLE_MS), defer")
            return true // 未达标，等待下一次周期扫描
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
        return com.neethu.gouxiang.chat.DurableMemoryProcessor(repo.db, extractor).run(character, conversationId)
    }

    // ── 离线主动消息 outbox(D 工作包 D2/D3/D4;§4 M1.2 语义升级)────

    /**
     * 主动消息扫描一轮(D2):开关关 → 直接跳过(不烧请求不重试);否则走
     * [ProactiveOutboxService.scanOnce] —— 轮转分页防多会话漏扫、逐会话
     * 共用「预约→生成→收口」链路、失败可重试。单会话失败继续扫其余会话,
     * 整体返回 false 触发 Worker retry。
     */
    private suspend fun executeProactiveOutbox(): Boolean {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        val service = proactiveService(repo)
        val report = service.scanOnce()
        for (r in report.reports) {
            when (r) {
                is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Generated ->
                    Log.i(TAG, "outbox generated conv=${r.conversationId} kind=${r.kind} count=${r.count} degraded=${r.degraded}")
                is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Failed ->
                    Log.w(TAG, "outbox failed conv=${r.conversationId}: ${r.reason}")
                is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Blocked ->
                    Log.i(TAG, "outbox blocked conv=${r.conversationId}: ${r.reason}")
                is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Skipped ->
                    Log.i(TAG, "outbox skipped conv=${r.conversationId}: ${r.reason}")
            }
        }
        return !report.anyFailed
    }

    /** 主动消息服务(生产装配:账本 = repo 单例的;生成 = orchestrator 包装;通知 = 通道二)。 */
    internal fun proactiveService(repo: com.neethu.gouxiang.data.GouXiangRepo) =
        com.neethu.gouxiang.proactive.ProactiveOutboxService(
            context = app,
            db = repo.db,
            ledger = repo.proactiveLedger,
            generate = com.neethu.gouxiang.proactive.ProactiveGeneration.production(app),
            notify = { n -> NotificationPoster.proactive(app, n.characterName, n.conversationId, n.count) },
            switch = { com.neethu.gouxiang.ai.GenerationPrefs.proactiveOutbox(app) },
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
