package com.neethu.gouxiang.derivatives

import android.content.Context
import androidx.room.withTransaction
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DerivativeFailure
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.chat.MemoryInjection
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.work.requireCurrentDerivativeSource
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * 手帐/小说/合照生成器(G 工作包 G1/G2/G3 的执行核心;Worker 的
 * HANDBOOK/NOVEL/PHOTO 分支委托,结构与 [com.neethu.gouxiang.sidestory.SidestoryGenerator] 同构):
 *
 * - 幂等:只在记录处于 generating 且 requestId 匹配时执行;成功收口走
 *   `markSucceededIfGenerating` 条件更新(重复收口/迟到旧任务不覆盖终态);
 * - 版本拦截:创建时快照来源范围与会话版本,请求前与提交事务内双重复验
 *   (回溯把版本 +1,在途旧候选直接判失败,不回写过期内容);
 * - 网络不入事务:LLM 请求与生图请求都在事务外完成,事务只做候选提交与
 *   列回写(公共约束 §3);
 * - schema 校验后才落库:解析失败重试一次,两次都坏 → DerivativeFailure(PARSE),
 *   Worker 拨记录失败(原始输出绝不入库);
 * - 文本先出、图片可选:手帐文本成功先收口,配图按开关独立执行,失败保留
 *   文字成品(imageStatus=failed);合照以图为主产物,失败整条记 failed 可重试;
 * - 中断自愈:成功记录若停在 imageStatus=pending(提交后、回填前被杀),
 *   重新执行同一 requestId 时补齐图片收口,不把「配图生成中」留成假象;
 * - 通知幂等:成功后经 notified 门禁投递一次(权限拒绝不阻塞内容保存)。
 */
class ChatDerivativeGenerator(
    private val context: Context,
    private val db: GouXiangDatabase,
    private val llm: ChatOrchestrator.Llm,
    /** 基础 LLM 配置(温度/输出预算由生成器按体裁覆盖)。 */
    private val llmConfig: () -> com.neethu.gouxiang.ai.adapter.LlmConfig,
    /** 配图执行缝(测试注入假件):入参 (cacheKey, prompt),返回落盘文件或 null。 */
    private val imageProvider: suspend (String, String) -> File?,
) {

    /** 手帐输出预算(3~8 段 × 300 字 + JSON 结构,1400 富余)。 */
    private val handbookMaxTokens = 1400

    /** @return true = 记录已收口(成功或幂等完成);false = 失败应记任务失败。 */
    suspend fun generate(recordId: Long, requestId: String, candidate: com.neethu.gouxiang.data.ContentCandidateEntity? = null): Boolean {
        val record = db.chatDerivativeDao().byId(recordId)
            ?: return true // 记录已被用户删除:不再生成,不判失败(幂等完成)
        if (record.requestId != requestId) return true // 旧任务迟到:新请求已接管
        if (record.state == ChatDerivativeEntity.STATE_SUCCEEDED) {
            // 中断自愈:文字已收口但配图回填被杀(仍 pending)→ 只补图,不重发 LLM。
            if (record.kind == ChatDerivativeEntity.KIND_HANDBOOK &&
                record.imageStatus == ChatDerivativeEntity.IMAGE_PENDING
            ) {
                return resumeHandbookImage(recordId)
            }
            return true
        }
        if (record.state != ChatDerivativeEntity.STATE_GENERATING) return true // failed 终态不自动重跑
        val conv = db.conversationDao().byId(record.conversationId)
            ?: return fail(recordId, "会话不存在，无法生成")
        val character = db.characterDao().byId(record.characterId)
            ?: return fail(recordId, "角色不存在，无法生成")
        return when (record.kind) {
            ChatDerivativeEntity.KIND_HANDBOOK -> generateHandbook(record, conv, character, requestId, candidate)
            ChatDerivativeEntity.KIND_NOVEL -> generateNovel(record, conv, character, requestId, candidate)
            ChatDerivativeEntity.KIND_PHOTO -> generatePhoto(record, conv, character, requestId)
            else -> fail(recordId, "未知的内容类型，无法生成")
        }
    }

    // ── G1 手帐 ──────────────────────────────────────────────────────

    private suspend fun generateHandbook(
        record: ChatDerivativeEntity,
        conv: ConversationEntity,
        character: CharacterEntity,
        requestId: String,
        candidate: com.neethu.gouxiang.data.ContentCandidateEntity?,
    ): Boolean {
        val option = DerivativeOptions.fromJson(record.kind, record.optionJson) as DerivativeOptions.HandbookOption
        // 版本拦截(请求前;创建时快照的版本与当前版本严格相等才放行,无例外)。
        if (!versionCurrent(conv.id, record.sourceVersion)) {
            return fail(record.id, "聊天历史已更改，请重新生成")
        }
        val rangeStart = record.sourceStartMessageId
        val rangeEnd = record.sourceEndMessageId
        val messages = if (rangeStart != null && rangeEnd != null) {
            db.messageDao().rangeForConversation(conv.id, rangeStart, rangeEnd)
        } else {
            emptyList()
        }
        val memoriesBlock = MemoryInjection.block(character.name, db.memoryDao().forCharacter(character.id), "")
        val system = ChatDerivativePrompts.handbookSystemPrompt(character, memoriesBlock)
        val user = ChatDerivativePrompts.sourceTurnsPrompt(
            character,
            com.neethu.gouxiang.work.DerivativeExecutor.toMemoryTurns(messages),
        )
        var validated = runWithRetry(system, user, handbookMaxTokens) { ChatDerivativeOutput.parseHandbook(it) }
            ?: throw DerivativeFailure.format()
        // 标题/心情兜底(模型漏字段不算失败,展示层回落类型名)。
        if (validated.title.isBlank()) validated = validated.copy(title = "手帐")
        val committed = db.withTransaction {
            val current = db.chatDerivativeDao().byId(record.id) ?: return@withTransaction true
            if (current.state != ChatDerivativeEntity.STATE_GENERATING) return@withTransaction true
            if (current.requestId != requestId) return@withTransaction true
            requireCurrentDerivativeSource(db, conv.id, record.sourceVersion)
            if (candidate != null) com.neethu.gouxiang.work.requireCurrentCandidate(db, candidate)
            val stamp = System.currentTimeMillis()
            val textCommitted = db.chatDerivativeDao().markSucceededIfGenerating(
                id = record.id,
                title = validated.title,
                bodyJson = ChatDerivativeOutput.handbookToJson(validated),
                at = stamp,
            ) > 0
            // 配图起始态同事务落定:开关关 = skipped(终态),开 = pending(回填中)
            db.chatDerivativeDao().setImageStatus(
                record.id,
                if (GenerationPrefs.handbookImages(context)) ChatDerivativeEntity.IMAGE_PENDING
                else ChatDerivativeEntity.IMAGE_SKIPPED,
                stamp,
            )
            textCommitted
        }
        if (!committed) return true
        return finishHandbookImage(record.id, character, validated.imagePrompt)
    }

    /** 手帐配图执行与收口(成功回填文件+complete;失败标 failed 保留文字)。 */
    private suspend fun finishHandbookImage(recordId: Long, character: CharacterEntity, imagePrompt: String): Boolean {
        if (!GenerationPrefs.handbookImages(context)) {
            db.chatDerivativeDao().setImageStatus(recordId, ChatDerivativeEntity.IMAGE_SKIPPED, System.currentTimeMillis())
            postNotification(recordId, character.name)
            return true
        }
        val prompt = "${character.appearance}。" +
            imagePrompt.ifBlank { "与用户相处的手帐回忆,温柔日常" } +
            "。${character.appearanceStyle},柔和手帐光影,竖版构图"
        val file = try {
            imageProvider("handbook-$recordId", prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (file != null) {
            db.chatDerivativeDao().setImageFile(recordId, file.absolutePath, System.currentTimeMillis())
        } else {
            db.chatDerivativeDao().setImageStatus(recordId, ChatDerivativeEntity.IMAGE_FAILED, System.currentTimeMillis())
        }
        postNotification(recordId, character.name)
        return true
    }

    /** 中断自愈:成功记录停在 pending → 只补图收口(幂等;不重发 LLM)。 */
    private suspend fun resumeHandbookImage(recordId: Long): Boolean {
        val record = db.chatDerivativeDao().byId(recordId) ?: return true
        val character = db.characterDao().byId(record.characterId) ?: return true
        return finishHandbookImage(recordId, character, ChatDerivativeOutput.handbookFromJson(record.bodyJson)?.imagePrompt.orEmpty())
    }

    // ── G2 小说 ──────────────────────────────────────────────────────

    private suspend fun generateNovel(
        record: ChatDerivativeEntity,
        conv: ConversationEntity,
        character: CharacterEntity,
        requestId: String,
        candidate: com.neethu.gouxiang.data.ContentCandidateEntity?,
    ): Boolean {
        val option = DerivativeOptions.fromJson(record.kind, record.optionJson) as DerivativeOptions.NovelOption
        if (!versionCurrent(conv.id, record.sourceVersion)) {
            return fail(record.id, "聊天历史已更改，请重新生成")
        }
        val rangeStart = record.sourceStartMessageId
        val rangeEnd = record.sourceEndMessageId
        val messages = if (rangeStart != null && rangeEnd != null) {
            db.messageDao().rangeForConversation(conv.id, rangeStart, rangeEnd)
        } else {
            emptyList()
        }
        val memoriesBlock = MemoryInjection.block(character.name, db.memoryDao().forCharacter(character.id), "")
        val system = ChatDerivativePrompts.novelSystemPrompt(character, memoriesBlock, option.length)
        val user = ChatDerivativePrompts.novelUserPrompt(
            character,
            com.neethu.gouxiang.work.DerivativeExecutor.toMemoryTurns(messages),
        )
        var validated = runWithRetry(system, user, option.length.maxTokens) {
            ChatDerivativeOutput.parseNovel(it, option.length)
        } ?: throw DerivativeFailure.format()
        if (validated.title.isBlank()) validated = validated.copy(title = "我们的小说")
        // 提交(短事务;版本复验 + 条件更新;无配图,imageStatus 终态 skipped 同事务落定)
        val committed = db.withTransaction {
            val current = db.chatDerivativeDao().byId(record.id) ?: return@withTransaction true
            if (current.state != ChatDerivativeEntity.STATE_GENERATING) return@withTransaction true
            if (current.requestId != requestId) return@withTransaction true
            requireCurrentDerivativeSource(db, conv.id, record.sourceVersion)
            if (candidate != null) com.neethu.gouxiang.work.requireCurrentCandidate(db, candidate)
            val stamp = System.currentTimeMillis()
            val textCommitted = db.chatDerivativeDao().markSucceededIfGenerating(
                id = record.id,
                title = validated.title,
                bodyJson = ChatDerivativeOutput.novelToJson(validated),
                at = stamp,
            ) > 0
            db.chatDerivativeDao().setImageStatus(record.id, ChatDerivativeEntity.IMAGE_SKIPPED, stamp)
            textCommitted
        }
        if (!committed) return true
        postNotification(record.id, character.name)
        return true
    }

    // ── G3 合照 ──────────────────────────────────────────────────────

    private suspend fun generatePhoto(
        record: ChatDerivativeEntity,
        conv: ConversationEntity,
        character: CharacterEntity,
        requestId: String,
    ): Boolean {
        val option = DerivativeOptions.fromJson(record.kind, record.optionJson) as DerivativeOptions.PhotoOption
        if (option.description.isBlank()) {
            return fail(record.id, "描述为空，请重新输入后生成")
        }
        if (!versionCurrent(conv.id, record.sourceVersion)) {
            return fail(record.id, "聊天历史已更改，请重新生成")
        }
        val prompt = ChatDerivativePrompts.photoPrompt(character, option.description)
        // 生图在事务外;失败整条记 failed(可重试),不制造假成功。
        val file = try {
            imageProvider("photo-${record.id}", prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (file == null) {
            return fail(record.id, "图片生成失败，请检查生图配置后重试")
        }
        val committed = db.withTransaction {
            val current = db.chatDerivativeDao().byId(record.id) ?: return@withTransaction true
            if (current.state != ChatDerivativeEntity.STATE_GENERATING) return@withTransaction true
            if (current.requestId != requestId) return@withTransaction true
            requireCurrentDerivativeSource(db, conv.id, record.sourceVersion)
            val stamp = System.currentTimeMillis()
            val textCommitted = db.chatDerivativeDao().markSucceededIfGenerating(
                id = record.id,
                title = option.description.take(12),
                bodyJson = ChatDerivativeOutput.photoToJson(option.description, prompt),
                at = stamp,
            ) > 0
            // 图与文字成品同事务收口(中断不会留下 pending 假象)。
            if (textCommitted) db.chatDerivativeDao().setImageFile(record.id, file.absolutePath, stamp)
            textCommitted
        }
        if (!committed) return true
        postNotification(record.id, character.name)
        return true
    }

    // ── 公共(版本/请求/失败/通知/对账)──────────────────────────────

    /** 会话版本严格相等才放行(与 captureDerivativeSource 的 0 缺省口径一致)。 */
    private suspend fun versionCurrent(conversationId: Long, snapshotVersion: Long): Boolean =
        (db.memoryWindowDao().version(conversationId) ?: 0L) == snapshotVersion

    /**
     * LLM 请求(事务外;解析失败重试一次,网络失败交 Worker 语义;输出预算
     * 按体裁覆盖——手帐/小说章数越多预算越高,防 JSON 截断)。
     */
    private suspend fun <T> runWithRetry(
        system: String,
        user: String,
        maxTokens: Int,
        parse: (String) -> T?,
    ): T? {
        val config = llmConfig().copy(temperature = 0.8f, maxTokens = maxTokens)
        var raw = request(system, user, config)
        var validated = raw?.let(parse)
        if (validated == null) {
            raw = request(system, user, config)
            validated = raw?.let(parse)
        }
        return validated
    }

    /** 单发非流式请求(InnerVoiceChannel.request 同款执行语义;失败抛可解释失败)。 */
    private suspend fun request(
        system: String,
        user: String,
        config: com.neethu.gouxiang.ai.adapter.LlmConfig,
    ): String {
        val sb = StringBuilder()
        var failure: Throwable? = null
        try {
            llm.streamChat(
                listOf(
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.SYSTEM, system),
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.USER, user),
                ),
                config,
            ).collect { event ->
                when (event) {
                    is com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta -> sb.append(event.text)
                    is com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Error -> failure = event.throwable
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw DerivativeFailure.from(t)
        }
        failure?.let { throw DerivativeFailure.from(it) }
        return sb.toString().takeIf { it.isNotBlank() } ?: throw DerivativeFailure.empty()
    }

    /** 失败收口(条件更新只在 generating 生效;返回 false 交 Worker 记任务失败)。 */
    private suspend fun fail(recordId: Long, reason: String): Boolean {
        db.chatDerivativeDao().markFailedIfGenerating(recordId, reason, System.currentTimeMillis())
        return false
    }

    /** 完成通知(幂等门;权限/开关拒绝仅降级为 App 内可见)。 */
    private suspend fun postNotification(recordId: Long, characterName: String) {
        db.withTransaction {
            val record = db.chatDerivativeDao().byId(recordId) ?: return@withTransaction
            if (!record.notified && record.state == ChatDerivativeEntity.STATE_SUCCEEDED) {
                com.neethu.gouxiang.work.NotificationPoster.chatDerivative(
                    context,
                    characterName,
                    record.conversationId,
                    record.kind,
                )
                db.chatDerivativeDao().markNotified(recordId)
            }
        }
    }

    // ── 重启对账(与 SidestoryGenerator.reconcileInterrupted 同思路)──

    /**
     * 记录行 generating 而任务已终态(失败/取消/跳过)的行拨失败;任务仍在队
     * 或在跑的不动;无任务行(入队前中断)直接拨失败。进程中断(生成中被杀)
     * 后进页调一次,失败状态可解释、可重试。
     *
     * @return 拨失败条数(日志用)。
     */
    suspend fun reconcileInterrupted(): Int {
        var fixed = 0
        for (record in db.chatDerivativeDao().generating()) {
            val task = record.requestId.takeIf { it.isNotBlank() }
                ?.let { db.derivativeTaskDao().byRequestId(it) }
            val reason = when {
                task == null -> "任务未完成，请重新生成"
                task.state in TERMINAL_FAILURE_TASK_STATES ->
                    task.failureReason?.takeIf { it.isNotBlank() } ?: "生成失败，请重新生成"
                else -> null
            }
            if (reason != null && db.chatDerivativeDao().markFailedIfGenerating(
                    record.id, reason, System.currentTimeMillis(),
                ) > 0
            ) {
                fixed++
            }
        }
        return fixed
    }

    companion object {
        /** 对账判定的任务终态(failed/cancelled/skipped;succeeded 的记录已被事务收口)。 */
        private val TERMINAL_FAILURE_TASK_STATES = setOf("failed", "cancelled", "skipped")

        /** 静默失败分类(单测断言 Worker 收口面用)。 */
        val PARSE_KIND: DerivativeFailureKind = DerivativeFailureKind.PARSE

        /**
         * 生产装配(Worker 分支与 VM 共用;LLM 缝与番外同源,输出预算由生成器
         * 按体裁覆盖)。
         */
        fun production(context: Context): ChatDerivativeGenerator {
            val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
            return ChatDerivativeGenerator(
                context = context,
                db = repo.db,
                llm = ChatOrchestrator.defaultLlm(),
                llmConfig = { com.neethu.gouxiang.work.memoryLlmConfig(context) },
                imageProvider = { key, prompt ->
                    imageProvider(context).getOrGeneratePhotoImage(key, prompt)
                },
            )
        }

        /** 生图 provider(进程内懒单例;与 VM 的各 provider 同惯例)。 */
        @Volatile private var imageProviderLazy: com.neethu.gouxiang.ai.image.AvatarImageProvider? = null

        private fun imageProvider(context: Context): com.neethu.gouxiang.ai.image.AvatarImageProvider =
            imageProviderLazy ?: synchronized(this) {
                imageProviderLazy ?: com.neethu.gouxiang.ai.image.AvatarImageProvider(
                    context.applicationContext.filesDir,
                ).also { imageProviderLazy = it }
            }
    }
}
