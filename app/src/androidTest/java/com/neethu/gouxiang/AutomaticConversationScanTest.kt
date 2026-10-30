package com.neethu.gouxiang

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticConversationScanTest {
    @Test
    fun scanCoversAllCharactersAndChoosesNewestImConversation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
        ).build()
        try {
            for (characterId in 1L..65L) {
                db.conversationDao().upsert(ConversationEntity(characterId = characterId, lastAt = characterId))
            }
            val olderId = db.conversationDao().upsert(ConversationEntity(characterId = 1, mode = "im", lastAt = 1000))
            val newestId = db.conversationDao().upsert(ConversationEntity(characterId = 1, mode = "im", lastAt = 1000))
            db.conversationDao().upsert(ConversationEntity(characterId = 2, mode = "phone", lastAt = 2000))
            db.conversationDao().upsert(ConversationEntity(characterId = 66, mode = "phone", lastAt = 2000))
            val scan = db.conversationDao().latestImPerCharacter()
            assertEquals(65, scan.size)
            assertEquals(65, scan.map { it.characterId }.toSet().size)
            assertEquals(newestId, scan.single { it.characterId == 1L }.id)
            assertFalse(scan.any { it.id == olderId })
            assertTrue(scan.all { it.mode == "im" })
            assertTrue(scan.any { it.characterId == 65L })
        } finally {
            db.close()
        }
    }
}
