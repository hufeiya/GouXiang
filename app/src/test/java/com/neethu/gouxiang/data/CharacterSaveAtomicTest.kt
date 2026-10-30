package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.protocol.ActionsJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C 工作包 C2:角色保存→首个 IM 会话+开场白的原子流程(单事务):
 * 建角色/建会话/落开场白/手机会话补建、连续两次保存不产生重复会话、
 * 编辑不动会话历史、形象改写只换头像版本、种子角色与既有历史不被重置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CharacterSaveAtomicTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val seed = db.seedDao()

    @After fun close() = db.close()

    private fun draft(
        name: String,
        opening: String = "(挥手)你好呀,初次见面。",
        appearance: String = "年轻女性,黑长直",
    ) = CharacterEntity(
        name = name,
        profile = "22岁,爱画画,称呼对话者为「用户」",
        intro = "初遇简介",
        exampleLines = "",
        openingLine = opening,
        appearance = appearance,
        appearanceStyle = "写实摄影风",
        createdAt = 100L,
    )

    @Test
    fun createWritesCharacterBothConversationsAndOpeningAtomically() = runBlocking {
        val saved = seed.saveCharacterAtomic(
            character = draft("测试甲"),
            openingContent = "你好呀,初次见面。",
            openingActions = listOf("挥手"),
            openingAt = 555L,
            appearanceChanged = false,
        )
        assertTrue(saved.created)
        assertTrue(saved.characterId > 0)
        // IM 会话按 ID 关联 + 开场白是会话里的第一条 assistant 消息
        val imConv = db.conversationDao().byCharacter(saved.characterId, "im")
        assertNotNull(imConv)
        assertEquals(saved.conversationId, imConv!!.id)
        assertEquals("你好呀,初次见面。", imConv.lastSummary)
        val messages = db.messageDao().allForConversation(imConv.id)
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("你好呀,初次见面。", messages[0].content)
        assertEquals(listOf("挥手"), ActionsJson.decode(messages[0].actionsJson))
        assertEquals(555L, messages[0].at)
        // 手机聊天会话行(C2 的手机入口路径,行为对齐种子)
        assertNotNull(db.conversationDao().byCharacter(saved.characterId, "phone"))
    }

    @Test
    fun secondTapOnSaveDoesNotDuplicateConversations() = runBlocking {
        val first = seed.saveCharacterAtomic(draft("测试乙"), "你好", emptyList(), 1L, false)
        val convCountAfterFirst = db.conversationDao().latest(100).size
        // 模拟双击漏过 UI 防抖:第二笔仍以「新建」身份提交 → 同名冲突,事务整体失败
        assertThrows(CharacterNameConflictException::class.java) {
            runBlocking {
                seed.saveCharacterAtomic(draft("测试乙"), "你好", emptyList(), 2L, false)
            }
        }
        assertEquals(convCountAfterFirst, db.conversationDao().latest(100).size)
        assertEquals(1, db.characterDao().count())
        // 即便拿到首次返回的会话,开场白也只有一条
        assertEquals(1, db.messageDao().allForConversation(first.conversationId).size)
    }

    @Test
    fun editKeepsConversationsAndHistoryUntouched() = runBlocking {
        val saved = seed.saveCharacterAtomic(draft("测试丙"), "开场白原文", emptyList(), 1L, false)
        val beforeConvs = db.conversationDao().latest(100).size
        // 编辑:改名外的字段,提交带原 id;开场白不重播
        val edited = saved.let {
            seed.characterByIdRow(it.characterId)!!.copy(profile = "新设定", intro = "新简介")
        }
        val result = seed.saveCharacterAtomic(
            character = edited,
            openingContent = "开场白原文",
            openingActions = emptyList(),
            openingAt = 999L,
            appearanceChanged = false,
        )
        assertTrue(!result.created)
        assertEquals(saved.characterId, result.characterId)
        assertEquals(saved.conversationId, result.conversationId)
        assertEquals(beforeConvs, db.conversationDao().latest(100).size)
        assertEquals("新设定", seed.characterByIdRow(saved.characterId)!!.profile)
        assertEquals(1, db.messageDao().allForConversation(saved.conversationId).size)
    }

    @Test
    fun editWithRewrittenAppearanceBumpsAvatarVersionAndClearsFile() = runBlocking {
        val saved = seed.saveCharacterAtomic(draft("测试丁"), "你好", emptyList(), 1L, false)
        db.characterDao().setAvatarFile(saved.characterId, "character-${saved.characterId}.jpg")
        // 形象未改写:版本/头像文件不动
        seed.saveCharacterAtomic(
            character = seed.characterByIdRow(saved.characterId)!!.copy(profile = "改设定"),
            openingContent = "你好", openingActions = emptyList(), openingAt = 2L,
            appearanceChanged = false,
        )
        val unchanged = seed.characterByIdRow(saved.characterId)!!
        assertEquals(0L, unchanged.avatarVersion)
        assertEquals("character-${saved.characterId}.jpg", unchanged.avatarFile)
        // 形象改写:版本 +1 且头像文件清空 → 版本化缓存键变化 → 生成新文件
        seed.saveCharacterAtomic(
            character = seed.characterByIdRow(saved.characterId)!!.copy(
                appearance = "年轻女性,银色短发",
                appearanceStyle = "水墨风",
            ),
            openingContent = "你好", openingActions = emptyList(), openingAt = 3L,
            appearanceChanged = true,
        )
        val bumped = seed.characterByIdRow(saved.characterId)!!
        assertEquals(1L, bumped.avatarVersion)
        assertEquals("", bumped.avatarFile)
    }

    @Test
    fun newCharacterDoesNotResetSeedsOrTheirHistory() = runBlocking {
        // 两颗「种子」:各自带会话、消息、记忆
        val seedA = seed.saveCharacterAtomic(draft("种子一"), "种子一开场", emptyList(), 10L, false)
        val seedB = seed.saveCharacterAtomic(draft("种子二"), "种子二开场", emptyList(), 20L, false)
        db.messageDao().insert(
            MessageEntity(
                conversationId = seedA.conversationId, turnId = "t1", role = "user",
                content = "种子一的历史消息", at = 30L,
            ),
        )
        db.memoryDao().insert(
            MemoryEntity(characterId = seedA.characterId, category = "event", content = "种子一的记忆", importance = 5, at = 40L),
        )
        // 新增角色
        val fresh = seed.saveCharacterAtomic(draft("测试戊"), "新角色开场", emptyList(), 50L, false)
        // 种子角色、会话、历史、记忆全部原样
        assertNotNull(seed.characterByName("种子一"))
        assertNotNull(seed.characterByName("种子二"))
        assertEquals("种子一的历史消息", db.messageDao().allForConversation(seedA.conversationId)[1].content)
        assertEquals(
            "种子一的记忆",
            db.memoryDao().forCharacter(seedA.characterId).single().content,
        )
        assertEquals(1, db.messageDao().allForConversation(seedB.conversationId).size)
        // 新角色自己的会话独立成行,不与种子互串
        assertEquals(fresh.conversationId, db.conversationDao().byCharacter(fresh.characterId, "im")!!.id)
        assertTrue(fresh.conversationId != seedA.conversationId && fresh.conversationId != seedB.conversationId)
    }

    @Test
    fun editRestoresMissingConversationExactlyOnce() = runBlocking {
        // 兜底路径:编辑一个「无会话」的既有角色(如旧版本数据),事务补建且不重复
        val charId = db.characterDao().upsert(draft("测试己"))
        val saved = seed.saveCharacterAtomic(
            character = seed.characterByIdRow(charId)!!,
            openingContent = "补建开场", openingActions = emptyList(), openingAt = 70L,
            appearanceChanged = false,
        )
        assertEquals(charId, saved.characterId)
        assertNotNull(db.conversationDao().byCharacter(charId, "im"))
        assertNotNull(db.conversationDao().byCharacter(charId, "phone"))
        val imCount = db.conversationDao().latest(100).count {
            it.characterId == charId && it.mode == "im"
        }
        assertEquals(1, imCount)
    }

    @Test
    fun nameConflictRollsBackWholeTransaction() = runBlocking {
        seed.saveCharacterAtomic(draft("占用名"), "你好", emptyList(), 1L, false)
        val beforeChars = db.characterDao().count()
        val beforeConvs = db.conversationDao().latest(100).size
        // 与既有角色同名的新建:在写入任何行之前即被拦截,无半套数据
        assertThrows(CharacterNameConflictException::class.java) {
            runBlocking {
                seed.saveCharacterAtomic(draft("占用名"), "你好", emptyList(), 2L, false)
            }
        }
        assertEquals(beforeChars, db.characterDao().count())
        assertEquals(beforeConvs, db.conversationDao().latest(100).size)
        assertNull(db.characterDao().byName("不存在的角色"))
    }
}
