package com.neethu.gouxiang.chat

import androidx.room.withTransaction
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MomentAuthorKind
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import com.neethu.gouxiang.data.MomentFeed
import com.neethu.gouxiang.data.MomentInteractionEntity
import com.neethu.gouxiang.data.MomentInteractionState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 角色互评(H3,10-moments.md §1.4「Ta们会互相评论」的本地化编排)。
 *
 * 候选选择是纯函数([MomentInteractions.candidates]),权限在查询层执行:
 * 参与([CharacterEntity.momentsJoined])、评论互动([CharacterEntity.momentsInteract])
 * 与看用户动态([CharacterEntity.momentsSeeUserPosts])三闸 + 已评论/已认领去重。
 *
 * 防循环与防重复的三重结构保证(路线图 H3,验收「一轮生成后停止」):
 * 1. 只有「新动态出现」触发一轮互评;评论本身不触发新轮次;
 * 2. (postId, characterId) 唯一认领 = 同角色对同动态至多一条互评,重放/重启/
 *    重复调用都不会再生成;
 * 3. 单轮候选数量封顶([MomentInteractions.MAX_COMMENTERS_PER_ROUND]),后台
 *    请求量有界(每条动态至多 N 次评论生成)。
 */
object MomentInteractions {

    /** 单轮互评的评论者上限(后台请求量护栏;本地角色量级下不触顶)。 */
    const val MAX_COMMENTERS_PER_ROUND = 4

    /**
     * 选出本轮应对 [post] 发评论的角色(保序:按传入角色序,调用方传稳定序)。
     *
     * @param claims 本动态已认领的角色 id 集(认领账本查询层依据)。
     */
    fun candidates(
        post: MomentEntity,
        characters: List<CharacterEntity>,
        existingComments: List<MomentCommentEntity>,
        claims: Set<Long>,
    ): List<CharacterEntity> {
        val commented = existingComments
            .filter { it.authorKind == MomentAuthorKind.AVATAR }
            .map { it.authorId }
            .toSet()
        return characters.asSequence()
            .filter { it.momentsJoined && it.momentsInteract }
            .filter { c -> post.authorKind != MomentAuthorKind.AVATAR || c.id != post.characterId }
            .filter { c -> post.authorKind != MomentAuthorKind.USER || c.momentsSeeUserPosts }
            .filter { c -> c.id !in commented }
            .filter { c -> c.id !in claims }
            .take(MAX_COMMENTERS_PER_ROUND)
            .toList()
    }
}

/**
 * 互评执行引擎(认领 → 生成 → 收口;网络调用一律在数据库事务外)。
 * 直接依赖 [GouXiangDatabase](生产装配传 repo.db,单测传内存库);失败安全:
 * 单角色生成失败只释放其认领,不影响其余候选,也不上抛(调用方无需处理)。
 */
class MomentInteractionEngine(
    private val db: com.neethu.gouxiang.data.GouXiangDatabase,
    private val channel: MomentsChannel,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * 对一条动态跑一轮互评(至多一轮;每角色一条)。@return 成功落库的评论数
     * (调用方用于调试观测;失败与无候选都返回已完成部分的计数)。
     */
    suspend fun runRound(post: MomentEntity): Int {
        val characters = db.characterDao().observeAll().first()
        val candidates = MomentInteractions.candidates(
            post = post,
            characters = characters,
            existingComments = db.momentCommentDao().forPost(post.id),
            claims = db.momentInteractionDao().claimedCharacterIds(post.id).toSet(),
        )
        val namesById = characters.associate { it.id to it.name }
        var committed = 0
        for (commenter in candidates) {
            committed += try {
                interactOne(post, commenter, namesById)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 失败释放认领(可重试),不留占位行、不阻塞其余候选
                runCatching { db.momentInteractionDao().release(post.id, commenter.id) }
                0
            }
        }
        return committed
    }

    /** 单角色:认领 → 生成 → 评论+终态同事务收口;失败释放认领后上抛。 */
    private suspend fun interactOne(
        post: MomentEntity,
        commenter: CharacterEntity,
        namesById: Map<Long, String>,
    ): Int {
        val claimed = db.momentInteractionDao().claim(
            MomentInteractionEntity(
                postId = post.id,
                characterId = commenter.id,
                requestId = requestId(),
                state = MomentInteractionState.RUNNING,
                at = clock(),
            ),
        )
        // 已被占(重复触发/进程中断残留)→ 幂等跳过,不重复生成
        if (claimed == -1L) return 0
        try {
            val system = MomentsGenerator.interactSystemPrompt(
                commenter,
                db.memoryDao().forCharacter(commenter.id),
            )
            val user = MomentsGenerator.interactUserPrompt(
                commenter = commenter,
                postAuthorName = postAuthorName(post, namesById),
                post = post,
                thread = db.momentCommentDao().forPost(post.id),
                namesById = namesById,
            )
            // LLM 请求在事务外(schema 校验后落库);评论 + 认领终态同一事务收口
            val raw = channel.requestReply(system, user)
            val text = raw?.let { MomentsGenerator.parseReply(it) }
                ?: throw DerivativeFailure.format()
            db.withTransaction {
                db.momentCommentDao().insert(
                    MomentCommentEntity(
                        postId = post.id,
                        authorKind = MomentAuthorKind.AVATAR,
                        authorId = commenter.id,
                        text = text,
                        at = clock(),
                    ),
                )
                db.momentInteractionDao().markState(post.id, commenter.id, MomentInteractionState.SUCCEEDED)
            }
            return 1
        } catch (t: Throwable) {
            db.momentInteractionDao().release(post.id, commenter.id)
            throw t
        }
    }

    /** 动态作者展示名(user 帖 = 用户;avatar 帖 = 角色名)。 */
    private fun postAuthorName(post: MomentEntity, namesById: Map<Long, String>): String =
        when (post.authorKind) {
            MomentAuthorKind.USER -> MomentFeed.USER_DISPLAY_NAME
            else -> namesById[post.characterId].orEmpty()
        }
}
