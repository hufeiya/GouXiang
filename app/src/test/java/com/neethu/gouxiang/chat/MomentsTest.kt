package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 朋友圈单测(假 LLM,零网络):动态/评论回复的宽松解析与清洗、实体盖章、
 * 请求通道失败安全、prompt 素材(人设/记忆/最近对话/评论线程)与无计费红线
 * (范围 5:锁卡/猫粮/解锁字样不得出现——原版「待解锁/邀请角色」不实现)。
 */
class MomentsTest {

    // ── 解析:宽松 JSON 与清洗 ───────────────────────────────────────

    @Test
    fun `parser accepts post json wrapped in chatter and markdown fences`() {
        val raw = "好:\n```json\n{\"text\":\"今天带用户去美术馆,他全程只帮我拎包,哼。\"," +
            "\"imagePrompt\":\"美术馆门口的奶茶与画板\"}\n```\n以上。"
        val draft = MomentsGenerator.parsePost(raw)
        assertNotNull(draft)
        assertEquals("今天带用户去美术馆,他全程只帮我拎包,哼。", draft!!.text)
        assertEquals("美术馆门口的奶茶与画板", draft.imagePrompt)
    }

    @Test
    fun `parser clamps overlong text and image prompt`() {
        val raw = "{\"text\":\"" + "长".repeat(200) + "\",\"imagePrompt\":\"" + "图".repeat(200) + "\"}"
        val draft = MomentsGenerator.parsePost(raw)
        assertNotNull(draft)
        assertEquals(MomentsGenerator.TEXT_MAX_CHARS, draft!!.text.length)
        assertEquals(MomentsGenerator.IMAGE_PROMPT_MAX_CHARS, draft.imagePrompt.length)
    }

    @Test
    fun `parser returns null for non-json wrong shape or empty text`() {
        assertNull(MomentsGenerator.parsePost("今天天气真好~"))
        assertNull(MomentsGenerator.parsePost("{\"voices\":[]}"))
        assertNull(MomentsGenerator.parsePost("{\"text\":\"\",\"imagePrompt\":\"x\"}"))
    }

    @Test
    fun `reply parser extracts text and rejects empty`() {
        assertEquals("好啦好啦,下次给你带一杯", MomentsGenerator.parseReply("{\"text\":\"好啦好啦,下次给你带一杯\"}"))
        assertNull(MomentsGenerator.parseReply("{\"text\":\"\"}"))
        assertNull(MomentsGenerator.parseReply("不想理你"))
    }

    @Test
    fun `to entity stamps character time and keeps image pending`() {
        val entity = MomentsGenerator.toEntity(
            MomentsGenerator.PostDraft("动态正文", "配图描述"),
            characterId = 7L,
            at = 123L,
        )
        assertEquals(7L, entity.characterId)
        assertEquals("动态正文", entity.text)
        assertEquals("配图描述", entity.imagePrompt)
        // 配图异步回填:落库时 imageFile 必为空(纯文字卡先行)
        assertEquals("", entity.imageFile)
        assertEquals(123L, entity.at)
    }

    // ── 通道:失败安全 ───────────────────────────────────────────────

    @Test
    fun `channel returns raw text on success and null on error stream`() = runTest {
        val ok = MomentsChannel(fakeLlm("{\"text\":\"ok\"}"), { config })
        assertEquals("{\"text\":\"ok\"}", ok.requestPost("s", "u"))
        val err = MomentsChannel(errorLlm(), { config })
        assertNull(err.requestPost("s", "u"))
        assertNull(err.requestReply("s", "u"))
        val empty = MomentsChannel(emptyLlm(), { config })
        assertNull(empty.requestPost("s", "u"))
    }

    // ── prompt:素材 ─────────────────────────────────────────────────

    @Test
    fun `post system prompt carries persona memories and json contract`() {
        val p = MomentsGenerator.postSystemPrompt(char, memories)
        assertTrue(p.contains("「苏馨玥」"))
        assertTrue(p.contains("[人设] 美术系大四学生"))
        assertTrue(p.contains("[记忆]"))
        assertTrue(p.contains("我的称呼:老公"))
        assertTrue(p.contains("\"text\""))
        assertTrue(p.contains("\"imagePrompt\""))
    }

    @Test
    fun `post user prompt replays recent turns`() {
        val u = MomentsGenerator.postUserPrompt(
            char,
            listOf(com.neethu.gouxiang.data.MemoryTurn("今天想去美术馆", "那周末一起去呀")),
        )
        assertTrue(u.contains("用户: 今天想去美术馆"))
        assertTrue(u.contains("苏馨玥: 那周末一起去呀"))
        val empty = MomentsGenerator.postUserPrompt(char, emptyList())
        assertTrue(empty.contains("还没有聊天记录"))
    }

    @Test
    fun `reply prompts carry post text thread and target comment`() {
        val post = MomentEntity(id = 5L, characterId = 1L, text = "美术馆的速写画完了", at = 1L)
        val thread = listOf(
            MomentCommentEntity(id = 9L, postId = 5L, authorKind = "user", text = "画里的奶茶呢", at = 2L),
        )
        val s = MomentsGenerator.replySystemPrompt(char, memories)
        val u = MomentsGenerator.replyUserPrompt(char, post, thread, thread.last())
        assertTrue(s.contains("「苏馨玥」"))
        assertTrue(s.contains("\"text\""))
        assertTrue(u.contains("[你的动态] 美术馆的速写画完了"))
        assertTrue(u.contains("用户: 画里的奶茶呢"))
        assertTrue(u.contains("[要回复的评论] 用户: 画里的奶茶呢"))
    }

    // ── 红线:无计费/无锁卡/无解锁(范围 5)──────────────────────────

    @Test
    fun `prompts never mention billing locking or invites`() {
        val post = MomentsGenerator.postSystemPrompt(char, memories)
        val reply = MomentsGenerator.replySystemPrompt(char, memories)
        (listOf(post, reply)).forEach { p ->
            assertFalse(p.contains("猫粮"))
            assertFalse(p.contains("扣费"))
            assertFalse(p.contains("解锁"))
            assertFalse(p.contains("待解锁"))
            assertFalse(p.contains("邀请角色"))
        }
    }

    // ── 基建 ────────────────────────────────────────────────────────

    private val char = CharacterEntity(
        id = 1L,
        name = "苏馨玥",
        profile = "美术系大四学生",
        intro = "i",
        exampleLines = "",
        openingLine = "o",
        appearance = "a",
        appearanceStyle = "写实摄影风",
        createdAt = 0,
    )

    private val memories = listOf(
        MemoryEntity(characterId = 1L, category = "relationship", content = "我的称呼:老公", importance = 9, at = 1),
        MemoryEntity(characterId = 1L, category = "event", content = "两人一起去了美术馆", importance = 5, at = 2),
    )

    private val config = LlmConfig(baseUrl = "http://localhost", apiKey = "k", model = "m")

    private fun fakeLlm(reply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow {
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(reply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    private fun errorLlm(): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow { emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Error(IllegalStateException("boom"))) }
    }

    private fun emptyLlm(): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ -> flow { } }
}
