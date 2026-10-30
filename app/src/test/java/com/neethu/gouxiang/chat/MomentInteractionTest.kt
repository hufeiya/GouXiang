package com.neethu.gouxiang.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MomentAuthorKind
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import com.neethu.gouxiang.data.MomentInteractionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H 工作包 H2/H3:角色互评(候选权限过滤/幂等认领/一轮为止防循环/失败释放
 * 可重试/无计费红线)的 Room 行为单测(内存库,真实唯一索引与事务边界)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MomentInteractionTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val config = LlmConfig(baseUrl = "http://localhost", apiKey = "k", model = "m")

    private var now = 1_900_000_000_000L
    private var charA = 0L
    private var charB = 0L

    @Before
    fun seed(): Unit = runBlocking {
        charA = db.seedDao().saveCharacterAtomic(
            character("甲"), "嗨", emptyList(), 1L, false,
        ).characterId
        charB = db.seedDao().saveCharacterAtomic(
            character("乙"), "嗨", emptyList(), 1L, false,
        ).characterId
    }

    @After fun close() = db.close()

    private fun character(name: String) = CharacterEntity(
        name = name, profile = "设定:$name", intro = "", exampleLines = "",
        openingLine = "嗨", appearance = "", appearanceStyle = "", createdAt = 1L,
    )

    private suspend fun insertUserPost(): MomentEntity {
        val id = db.momentDao().insert(
            MomentEntity(characterId = 0, authorKind = MomentAuthorKind.USER, text = "用户发的一条动态", at = 10),
        )
        return db.momentDao().byId(id)!!
    }

    private fun engine(reply: String) = MomentInteractionEngine(
        db = db,
        channel = MomentsChannel(llm(reply), { config }),
        clock = { ++now },
        requestId = { "req-$now" },
    )

    // ── H2 候选过滤(查询层权限)──────────────────────────────────────

    @Test
    fun candidatesFilterByPermissionsSelfAndAlreadyCommentedOrClaimed() = runBlocking {
        val post = insertUserPost()
        // 权限矩阵:乙 不可看用户动态 → 用户帖下无资格;甲 全开 → 唯一候选
        db.characterDao().update(db.characterDao().byId(charB)!!.copy(momentsSeeUserPosts = false))
        val all = db.characterDao().observeAll().first()
        assertEquals(listOf(charA), MomentInteractions.candidates(post, all, emptyList(), emptySet()).map { it.id })
        // 关评论互动 → 无候选
        db.characterDao().update(db.characterDao().byId(charA)!!.copy(momentsInteract = false))
        assertEquals(
            emptyList<Long>(),
            MomentInteractions.candidates(post, db.characterDao().observeAll().first(), emptyList(), emptySet()).map { it.id },
        )
        // 不参与朋友圈 → 无候选
        db.characterDao().update(db.characterDao().byId(charA)!!.copy(momentsJoined = false))
        assertTrue(MomentInteractions.candidates(post, db.characterDao().observeAll().first(), emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun candidatesExcludeSelfAuthorExistingAvatarCommentsClaimsAndCapAtFour() = runBlocking {
        val postId = db.momentDao().insert(
            MomentEntity(characterId = charA, authorKind = MomentAuthorKind.AVATAR, text = "甲的动态", at = 11),
        )
        val post = db.momentDao().byId(postId)!!
        val all = listOf(
            db.characterDao().byId(charA)!!,   // 动态作者本人 → 排除
            db.characterDao().byId(charB)!!,
            character("丙").copy(id = 3),
            character("丁").copy(id = 4),
            character("戊").copy(id = 5),
            character("己").copy(id = 6),
        )
        // 甲(作者)排除;上限 4 → 乙/丙/丁/戊
        assertEquals(
            listOf(charB, 3L, 4L, 5L),
            MomentInteractions.candidates(post, all, emptyList(), emptySet()).map { it.id },
        )
        // 已有乙的 avatar 评论 → 乙排除;丁 已认领 → 排除;剩 丙/戊/己(上限4未触顶)
        val commented = listOf(
            MomentCommentEntity(postId = postId, authorKind = MomentAuthorKind.AVATAR, authorId = charB, text = "乙评过", at = 12),
        )
        assertEquals(
            listOf(3L, 5L, 6L),
            MomentInteractions.candidates(post, all, commented, claims = setOf(4L)).map { it.id },
        )
    }

    // ── H3 引擎:一轮为止 / 失败释放可重试 ──────────────────────────

    @Test
    fun roundCommentsOncePerCharacterAndSecondRoundAddsNothing() = runBlocking {
        val post = insertUserPost()
        val interactions = engine("{\"text\":\"这条动态真有意思\"}")
        assertEquals(2, interactions.runRound(post))
        val comments = db.momentCommentDao().forPost(post.id)
        assertEquals(2, comments.size)
        assertTrue(comments.all { it.authorKind == MomentAuthorKind.AVATAR && it.text == "这条动态真有意思" })
        // 每条评论都归名到具体角色(H1 评论者模型)
        assertEquals(setOf(charA, charB), comments.map { it.authorId }.toSet())
        // 认领账本全部置终态(状态机 running→succeeded)
        assertTrue(db.momentInteractionDao().forPost(post.id).all { it.state == MomentInteractionState.SUCCEEDED })
        // 一轮为止:再跑一轮不新增(评论不触发新轮次 + 唯一认领幂等)
        assertEquals(0, interactions.runRound(post))
        assertEquals(2, db.momentCommentDao().forPost(post.id).size)
    }

    @Test
    fun failedGenerationReleasesClaimKeepsNoPlaceholderAndCanRetry() = runBlocking {
        val post = insertUserPost()
        // 解析失败(LLM 输出非 JSON)→ 认领释放、零占位行、零评论
        assertEquals(0, engine("这不是 JSON").runRound(post))
        assertEquals(0, db.momentCommentDao().forPost(post.id).size)
        assertTrue(db.momentInteractionDao().forPost(post.id).isEmpty())
        // 释放后可重试:同角色同动态再跑成功
        assertEquals(2, engine("{\"text\":\"重试成功\"}").runRound(post))
        assertEquals(2, db.momentCommentDao().forPost(post.id).size)
        // 清场:释放并删除双方评论,验证「单角色失败不影响其余候选」
        db.momentInteractionDao().release(post.id, charA)
        db.momentInteractionDao().release(post.id, charB)
        db.momentCommentDao().forPost(post.id).forEach { db.momentCommentDao().deleteById(it.id) }
        // 预占乙(模拟并发在途)→ 本轮候选只剩甲
        db.momentInteractionDao().claim(
            com.neethu.gouxiang.data.MomentInteractionEntity(
                postId = post.id, characterId = charB, requestId = "req-x",
                state = MomentInteractionState.RUNNING, at = now,
            ),
        )
        // 甲 通道坏:失败释放认领、零占位行;乙的预占不受影响
        val bad = MomentInteractionEngine(
            db = db,
            channel = MomentsChannel(errorLlm(), { config }),
            clock = { ++now }, requestId = { "req-$now" },
        )
        assertEquals(0, bad.runRound(post))
        assertTrue(db.momentCommentDao().forPost(post.id).isEmpty())
        assertTrue(db.momentInteractionDao().forPost(post.id).none { it.characterId == charA })
        val rowsAfterBad = db.momentInteractionDao().forPost(post.id)
        assertEquals("rows=$rowsAfterBad", listOf(charB), rowsAfterBad.map { it.characterId })
        // 换好通道再跑:甲成功落 1 条;乙的预占不被重复生成(仍只有乙一条评论缺席)
        val good = MomentInteractionEngine(
            db = db,
            channel = MomentsChannel(llm("{\"text\":\"只有甲成功\"}"), { config }),
            clock = { ++now }, requestId = { "req-$now" },
        )
        assertEquals(1, good.runRound(post))
        val commentsAfterGood = db.momentCommentDao().forPost(post.id)
        assertEquals(1, commentsAfterGood.size)
        assertEquals(charA, commentsAfterGood.single().authorId)
        // 乙保持预占(running),未被 good 轮重复生成
        val rowB = db.momentInteractionDao().forPost(post.id).first { it.characterId == charB }
        assertEquals(MomentInteractionState.RUNNING, rowB.state)
    }

    @Test
    fun preExistingClaimIsNotRegenerated() = runBlocking {
        val post = insertUserPost()
        // 进程中断残留的 running 认领:重启后再触发不重复生成
        db.momentInteractionDao().claim(
            com.neethu.gouxiang.data.MomentInteractionEntity(
                postId = post.id, characterId = charA, requestId = "req-stale",
                state = MomentInteractionState.RUNNING, at = now,
            ),
        )
        assertEquals(1, engine("{\"text\":\"只有乙评\"}").runRound(post))
        val comments = db.momentCommentDao().forPost(post.id)
        assertEquals(1, comments.size)
        assertEquals(charB, comments.single().authorId)
    }

    // ── 红线:互评 prompt 无计费字样 ─────────────────────────────────

    @Test
    fun interactionPromptsNeverMentionBillingWords() = runBlocking {
        val commenter = db.characterDao().byId(charA)!!
        val post = insertUserPost()
        val s = MomentsGenerator.interactSystemPrompt(commenter, emptyList())
        val u = MomentsGenerator.interactUserPrompt(commenter, "我", post, emptyList(), emptyMap())
        listOf(s, u).forEach { p ->
            listOf("计费", "猫粮", "会员", "扣费", "解锁").forEach { word ->
                assertFalse("红线字样「$word」出现于互评 prompt", p.contains(word))
            }
        }
        // prompt 带评论者人设与作者名(按可见角色人设生成)
        assertTrue(s.contains("「甲」"))
        assertTrue(u.contains("[我的动态] ${post.text}"))
    }

    // ── 基建 ────────────────────────────────────────────────────────

    private fun llm(fakeReply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow {
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(fakeReply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    private fun errorLlm(): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow { emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Error(IllegalStateException("boom"))) }
    }
}
