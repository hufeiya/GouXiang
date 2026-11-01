package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogImportTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val seed = db.seedDao()

    @After fun close() = db.close()

    private fun character(id: String) = CharacterEntity(
        name = "同名角色", profile = "原始设定", intro = "简介", exampleLines = "",
        openingLine = "（挥手）你好", appearance = "", appearanceStyle = "",
        source = "maoxiang:$id", createdAt = 10,
    )

    @Test fun catalogReadsAudienceAndDefaultsMissingValuesToUnknown() {
        val rows = CatalogRepository.parse(
            """[{"sourceId":"1234567890123456789","name":"女性向","audience":"female"},
                {"sourceId":"2","name":"既有角色","audience":"unknown"},
                {"sourceId":"3","name":"旧数据"}]""",
        )
        assertEquals(listOf("female", "unknown", "unknown"), rows.map { it.audience })
        assertEquals("1234567890123456789", rows.first().sourceId)
    }

    @Test fun startsIdempotentlyAndKeepsUserEditsAndHistory() = runBlocking {
        val original = character("1")
        val first = seed.startCatalogChat(original, 10)
        assertEquals("（挥手）你好", db.messageDao().allForConversation(first.conversationId).single().content)
        seed.updateCharacter(seed.characterByIdRow(first.characterId)!!.copy(profile = "用户修改"))
        db.messageDao().insert(MessageEntity(conversationId = first.conversationId, turnId = "t1", role = "user", content = "消息", at = 20))
        val again = seed.startCatalogChat(original.copy(profile = "来源更新", openingLine = "新开场"), 30)
        assertEquals(first.characterId, again.characterId)
        assertEquals(first.conversationId, again.conversationId)
        assertFalse(again.created)
        assertEquals("用户修改", seed.characterByIdRow(first.characterId)!!.profile)
        assertEquals(listOf("（挥手）你好", "消息"), db.messageDao().allForConversation(first.conversationId).map { it.content })
        assertEquals(2, db.conversationDao().latest(100).size)
    }

    @Test fun sameNameDifferentSourcesRemainDistinctAndEditable() = runBlocking {
        val first = seed.startCatalogChat(character("1"), 10)
        val second = seed.startCatalogChat(character("2"), 20)
        assertNotEquals(first.characterId, second.characterId)
        assertNotEquals(first.conversationId, second.conversationId)
        val edited = seed.characterByIdRow(second.characterId)!!.copy(profile = "编辑同名角色")
        seed.saveCharacterAtomic(edited, edited.openingLine, emptyList(), 30, false)
        assertEquals("编辑同名角色", seed.characterByIdRow(second.characterId)!!.profile)
        assertEquals("原始设定", seed.characterByIdRow(first.characterId)!!.profile)
    }
}
