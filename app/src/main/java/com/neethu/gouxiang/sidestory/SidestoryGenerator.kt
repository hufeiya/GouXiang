package com.neethu.gouxiang.sidestory

import android.content.Context
import androidx.room.withTransaction
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DerivativeFailure
import com.neethu.gouxiang.chat.InnerVoiceChannel
import com.neethu.gouxiang.chat.MemoryInjection
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.SidestoryRecordEntity
import com.neethu.gouxiang.work.captureDerivativeSource
import com.neethu.gouxiang.work.requireCurrentDerivativeSource
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * 番外/心动生成器(F 工作包 F3/F4 的执行核心;Worker 的 SIDESTORY 分支委托):
 *
 * - 幂等:只在记录处于 generating 且 requestId 匹配时执行;成功收口走
 *   `markSucceededIfGenerating` 条件更新(重复收口/迟到旧任务不覆盖终态);
 * - 版本拦截:创建时快照来源范围与会话版本,提交事务内复验(回溯把版本 +1,
 *   在途旧候选直接判失败,不回写过期内容;与心声/朋友圈 requireCurrent 同源);
 * - 网络不入事务:LLM 请求与配图请求都在事务外完成,事务只做候选提交与
 *   列回写(公共约束 §3);
 * - schema 校验后才落库:解析失败重试一次(06 专项「格式坏重试一次仍失败
 *   =FAILED」),两次都坏 → DerivativeFailure(PARSE),Worker 拨记录失败;
 * - 文本先出、图片可选:文本成功先收口;配图按开关与模板声明独立执行,
 *   失败保留文字成品(imageStatus=failed,列表/详情明确提示);
 * - 通知幂等:成功后经 notified 门禁投递一次(权限拒绝不阻塞内容保存)。
 */
class SidestoryGenerator(
    private val context: Context,
    private val db: GouXiangDatabase,
    private val llm: ChatOrchestrator.Llm,
    private val llmConfig: () -> com.neethu.gouxiang.ai.adapter.LlmConfig,
    /** 配图执行缝(测试注入假件):入参 (cacheKey, prompt),返回落盘文件或 null。 */
    private val imageProvider: suspend (String, String) -> File?,
) {

    private val channel = InnerVoiceChannel(llm, llmConfig)

    /** @return true = 记录已收口(成功或幂等完成);false = 失败应记任务失败。 */
    suspend fun generate(recordId: Long, requestId: String): Boolean {
        val record = db.sidestoryRecordDao().byId(recordId)
            ?: return true // 记录已被用户删除:不再生成,不判失败(幂等完成)
        if (record.state != SidestoryRecordEntity.STATE_GENERATING) return true // 已收口
        if (record.requestId != requestId) return true // 旧任务迟到:新请求已接管
        val conv = db.conversationDao().byId(record.conversationId)
            ?: return fail(record, "会话不存在，无法生成")
        val character = db.characterDao().byId(record.characterId)
            ?: return fail(record, "角色不存在，无法生成")
        val entry = SidestoryCatalog.byKey(record.templateKey)
            ?: return fail(record, "模板不存在或已下架，请重新选择")

        // 来源快照(事务内读版本+素材,网络前完成);版本被回溯推进即拒绝
        // (创建时快照的版本与当前版本严格相等才放行,无例外)。
        val source = captureDerivativeSource(db, conv.id, character.id)
        if (record.sourceVersion != source.version) {
            return fail(record, "聊天历史已更改，请重新生成")
        }
        val status = if (entry.usesCharacterStatus) {
            db.characterStatusDao().latestActive(character.id)
        } else {
            null
        }
        val memoriesBlock = MemoryInjection.block(character.name, source.memories, "")

        // LLM 请求(事务外;解析失败重试一次,网络失败交 Worker 语义)。
        val system = SidestoryPrompts.systemPrompt(character, entry, memoriesBlock)
        val user = SidestoryPrompts.userPrompt(character, entry, turnsOf(source.messages), status)
        var raw = channel.request(system, user, strict = true)
        var validated = raw?.let { SidestoryOutput.parse(it, entry) }
        if (validated == null) {
            raw = channel.request(system, user, strict = true)
            validated = raw?.let { SidestoryOutput.parse(it, entry) }
        }
        val result = validated ?: throw DerivativeFailure.format()

        // 候选提交(短事务;版本复验 + 条件更新防迟到旧任务覆盖新终态)。
        val committed = db.withTransaction {
            val current = db.sidestoryRecordDao().byId(recordId) ?: return@withTransaction true
            if (current.state != SidestoryRecordEntity.STATE_GENERATING) return@withTransaction true
            if (current.requestId != requestId) return@withTransaction true
            requireCurrentDerivativeSource(db, conv.id, source.version)
            val stamp = System.currentTimeMillis()
            db.sidestoryRecordDao().markSucceededIfGenerating(
                id = recordId,
                title = result.title,
                bodyJson = SidestoryOutput.toJson(result),
                at = stamp,
            ) > 0
        }
        if (!committed) return true

        // 配图(可选;文本已收口,失败只标图状态,不回滚文字成品)。
        // 图是「角色形象+基调」的独立画面,不锚定消息,无需版本复验。
        // 终态必写(模板无图或开关关 = skipped),防成功记录停在 pending 假象。
        val wantImage = entry.optionalImage && GenerationPrefs.sidestoryImages(context)
        val imageStatus = if (!wantImage) {
            SidestoryRecordEntity.IMAGE_SKIPPED
        } else {
            val prompt = buildImagePrompt(character, entry, result)
            val file = try {
                imageProvider("sidestory-$recordId", prompt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (file != null) {
                db.sidestoryRecordDao().setImageFile(recordId, file.absolutePath, System.currentTimeMillis())
                SidestoryRecordEntity.IMAGE_COMPLETE
            } else {
                SidestoryRecordEntity.IMAGE_FAILED
            }
        }
        db.sidestoryRecordDao().setImageStatus(recordId, imageStatus, System.currentTimeMillis())
        postNotification(recordId, character.name)
        return true
    }

    /** 文本生成成功后的完成通知(幂等门;权限/开关拒绝仅降级为 App 内可见)。 */
    private suspend fun postNotification(recordId: Long, characterName: String) {
        db.withTransaction {
            val record = db.sidestoryRecordDao().byId(recordId) ?: return@withTransaction
            if (!record.notified && record.state == SidestoryRecordEntity.STATE_SUCCEEDED) {
                com.neethu.gouxiang.work.NotificationPoster.sidestory(
                    context,
                    characterName,
                    record.conversationId,
                )
                db.sidestoryRecordDao().markNotified(recordId)
            }
        }
    }

    /** 失败收口(条件更新只在 generating 生效;返回 false 交 Worker 记任务失败)。 */
    private suspend fun fail(record: SidestoryRecordEntity, reason: String): Boolean {
        db.sidestoryRecordDao().markFailedIfGenerating(record.id, reason, System.currentTimeMillis())
        return false
    }

    /** 封面图 prompt:角色形象 + 首格画面描述 + 风格词(心声/朋友圈配图同款拼法)。 */
    private fun buildImagePrompt(
        character: com.neethu.gouxiang.data.CharacterEntity,
        entry: SidestoryCatalog.Entry,
        result: SidestoryOutput.Validated,
    ): String {
        val mood = result.pages.firstNotNullOfOrNull { it.imagePrompt.takeIf(String::isNotBlank) }
            ?: entry.subtitle
        return "${character.appearance}。$mood。${character.appearanceStyle},柔和电影感光影,竖版构图"
    }

    private fun turnsOf(messages: List<com.neethu.gouxiang.data.MessageEntity>): List<com.neethu.gouxiang.data.MemoryTurn> =
        com.neethu.gouxiang.work.DerivativeExecutor.toMemoryTurns(messages)

    // ── 重启对账(与 reconcileDerivativeTasks 同思路)──────────────────

    /**
     * 记录行 generating 而任务已终态(失败/取消/跳过)的行拨失败;任务仍在队
     * 或在跑的不动;无任务行(入队前中断)直接拨失败。进程中断(生成中被杀)
     * 后进页调一次,失败状态可解释、可重试。
     *
     * @return 拨失败条数(日志用)。
     */
    suspend fun reconcileInterrupted(): Int {
        var fixed = 0
        for (record in db.sidestoryRecordDao().generating()) {
            val task = record.requestId.takeIf { it.isNotBlank() }
                ?.let { db.derivativeTaskDao().byRequestId(it) }
            val reason = when {
                task == null -> "任务未完成，请重新生成"
                task.state in TERMINAL_FAILURE_TASK_STATES ->
                    task.failureReason?.takeIf { it.isNotBlank() } ?: "生成失败，请重新生成"
                else -> null
            }
            if (reason != null && db.sidestoryRecordDao().markFailedIfGenerating(
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

        /**
         * 生产装配(Worker 分支与 VM 共用;LLM 缝与心声同源,番外配置放宽
         * maxTokens——查手机 11 分区输出较长,512 会截断)。
         */
        fun production(context: Context): SidestoryGenerator {
            val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
            return SidestoryGenerator(
                context = context,
                db = repo.db,
                llm = ChatOrchestrator.defaultLlm(),
                llmConfig = { sidestoryLlmConfig(context) },
                imageProvider = { key, prompt ->
                    imageProvider(context).getOrGenerateSidestoryImage(key, prompt)
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

        /**
         * 番外 LLM 配置:temperature 0.8(创作向,与心声一致)、maxTokens 1400
         * (查手机 11 分区 + 吃醋多格,512 会截断)。
         */
        fun sidestoryLlmConfig(context: Context): com.neethu.gouxiang.ai.adapter.LlmConfig =
            com.neethu.gouxiang.work.memoryLlmConfig(context)
                .copy(temperature = 0.8f, maxTokens = 1400)
    }
}
