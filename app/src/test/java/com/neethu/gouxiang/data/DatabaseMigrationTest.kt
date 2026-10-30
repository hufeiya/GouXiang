package com.neethu.gouxiang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens an actual v5 schema through Room, so migration validation covers every table and index. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-v5-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun createV5() {
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val schema = listOf(
            "CREATE TABLE `characters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `profile` TEXT NOT NULL, `intro` TEXT NOT NULL, `exampleLines` TEXT NOT NULL, `openingLine` TEXT NOT NULL, `appearance` TEXT NOT NULL, `appearanceStyle` TEXT NOT NULL, `avatarFile` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` INTEGER NOT NULL, `mode` TEXT NOT NULL, `lastSummary` TEXT NOT NULL, `lastAt` INTEGER NOT NULL, `unread` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX `index_conversations_characterId` ON `conversations` (`characterId`)",
            "CREATE TABLE `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `turnId` TEXT NOT NULL, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, `actionsJson` TEXT NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX `index_messages_conversationId` ON `messages` (`conversationId`)",
            "CREATE INDEX `index_messages_turnId` ON `messages` (`turnId`)",
            "CREATE TABLE `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` INTEGER NOT NULL, `category` TEXT NOT NULL, `subtype` TEXT NOT NULL, `content` TEXT NOT NULL, `importance` INTEGER NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX `index_memories_characterId` ON `memories` (`characterId`)",
            "CREATE TABLE `pending_memory_window` (`id` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `inner_voices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` INTEGER NOT NULL, `title` TEXT NOT NULL, `echo` TEXT NOT NULL, `imageFile` TEXT NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX `index_inner_voices_characterId` ON `inner_voices` (`characterId`)",
            "CREATE TABLE `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` INTEGER NOT NULL, `text` TEXT NOT NULL, `imagePrompt` TEXT NOT NULL, `imageFile` TEXT NOT NULL, `likedByMe` INTEGER NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX `index_moments_characterId` ON `moments` (`characterId`)",
            "CREATE TABLE `moment_comments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `postId` INTEGER NOT NULL, `authorKind` TEXT NOT NULL, `replyToId` INTEGER, `text` TEXT NOT NULL, `at` INTEGER NOT NULL)",
            "CREATE INDEX `index_moment_comments_postId` ON `moment_comments` (`postId`)",
        )
        schema.forEach(db::execSQL)
        db.execSQL("INSERT INTO characters VALUES (1, '角色', '设定', '', '', '', '', '', '', 'local', 1)")
        db.execSQL("INSERT INTO conversations VALUES (2, 1, 'im', '摘要', 3, 0, 1)")
        db.execSQL("INSERT INTO messages VALUES (3, 2, 'turn', 'user', 'text', '聊天', '', 3)")
        db.execSQL("INSERT INTO memories VALUES (4, 1, 'event', '', '记忆', 5, 4)")
        db.execSQL("INSERT INTO pending_memory_window VALUES (0, ?)", arrayOf("\u0001\u0001主动消息\u0002\u0001用户\u0001回答"))
        db.execSQL("INSERT INTO inner_voices VALUES (5, 1, '心声', '内容', '', 5)")
        db.execSQL("INSERT INTO moments VALUES (6, 1, '动态', '', '', 0, 6)")
        db.version = 5
        db.close()
    }

    @Test fun v5DataAndUnattributedWindowSurviveMigration() {
        createV5()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(
                GouXiangDatabase.MIGRATION_5_6,
                GouXiangDatabase.MIGRATION_6_7,
                GouXiangDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            assertEquals("记忆", scalar("SELECT content FROM memories WHERE id=4"))
            assertEquals("心声", scalar("SELECT title FROM inner_voices WHERE id=5"))
            assertEquals("动态", scalar("SELECT text FROM moments WHERE id=6"))
            assertEquals("\u0001\u0001主动消息\u0002\u0001用户\u0001回答", scalar("SELECT payload FROM pending_memory_window WHERE id=0"))
        } finally { db.close() }
    }

    @Test fun missingMigrationDoesNotDestroyOldData() {
        createV5()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
        finally { db.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(5, raw.version)
            raw.rawQuery("SELECT content FROM messages WHERE id=3", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("聊天", cursor.getString(0))
            }
        }
    }

    /** v5 库 + MIGRATION_5_6 的全部语句 = 真实 v6 库(供 6→7 迁移测试)。 */
    private fun createV6FromV5WithTaskHistoryRow() {
        createV5()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS `derivative_tasks` (`workId` TEXT NOT NULL, `requestId` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, `conversationId` INTEGER NOT NULL, `characterId` INTEGER NOT NULL, `state` TEXT NOT NULL, `failureReason` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`workId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `derivative_results` (`requestId` TEXT NOT NULL, `kind` TEXT NOT NULL, `characterId` INTEGER NOT NULL, `resultIds` TEXT NOT NULL, `imageStatus` TEXT NOT NULL, `sourceConversationId` INTEGER NOT NULL, `sourceStartMessageId` INTEGER, `sourceEndMessageId` INTEGER, `sourceVersion` INTEGER NOT NULL, `notificationDone` INTEGER NOT NULL, PRIMARY KEY(`requestId`))")
        db.execSQL("ALTER TABLE moments ADD COLUMN isStale INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE inner_voices ADD COLUMN isStale INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN sourceConversationId INTEGER")
        db.execSQL("ALTER TABLE memories ADD COLUMN sourceEndMessageId INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS `memory_turns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `characterId` INTEGER NOT NULL, `turnId` TEXT NOT NULL, `startMessageId` INTEGER NOT NULL, `endMessageId` INTEGER NOT NULL, `user` TEXT NOT NULL, `assistant` TEXT NOT NULL, `completed` INTEGER NOT NULL, `profileDone` INTEGER NOT NULL, `eventDone` INTEGER NOT NULL, `profileLease` TEXT NOT NULL, `eventLease` TEXT NOT NULL, `profileLeaseAt` INTEGER NOT NULL, `eventLeaseAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_turns_conversationId` ON `memory_turns` (`conversationId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_turns_conversationId_turnId` ON `memory_turns` (`conversationId`, `turnId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `memory_conversation_versions` (`conversationId` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
        // 一条开发期失败任务:6→7 后状态/原因保留,新列 failureKind 为 NULL
        db.execSQL(
            "INSERT INTO derivative_tasks VALUES ('w1', 'r1', 'MOMENT', 'MANUAL', 2, 1, " +
                "'failed', '生成失败，请检查网络与模型配置后重试', 10, 10, 10)",
        )
        db.version = 6
        db.close()
    }

    @Test fun v6TaskHistoryGainsFailureKindColumnAndKeepsRows() {
        createV6FromV5WithTaskHistoryRow()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_6_7, GouXiangDatabase.MIGRATION_7_8)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            // 既有行原样保留;新列可读且为 NULL(展示层回落「未知」)
            assertEquals("w1", scalar("SELECT workId FROM derivative_tasks"))
            assertEquals("failed", scalar("SELECT state FROM derivative_tasks"))
            assertEquals("生成失败，请检查网络与模型配置后重试", scalar("SELECT failureReason FROM derivative_tasks"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            // 新列可写(Worker 失败收口的落库面)
            sql.execSQL("UPDATE derivative_tasks SET failureKind = 'NETWORK' WHERE workId = 'w1'")
            assertEquals("NETWORK", scalar("SELECT failureKind FROM derivative_tasks"))
        } finally { db.close() }
    }

    /** v7 库 = v6 全部语句 + failureKind 列(照 6→7 真实迁移顺序)。 */
    private fun createV7FromV6() {
        createV6FromV5WithTaskHistoryRow()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL("ALTER TABLE derivative_tasks ADD COLUMN failureKind TEXT")
        db.version = 7
        db.close()
    }

    @Test fun v7CharactersGainAvatarVersionColumnAndKeepsRows() {
        createV7FromV6()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_7_8)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            fun longScalar(query: String): Long = sql.query(query).use { it.moveToFirst(); it.getLong(0) }
            // 角色行及其会话/消息原样保留;新列回填 0(从未按版本重生成)
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals(0L, longScalar("SELECT avatarVersion FROM characters WHERE id=1"))
            assertEquals("摘要", scalar("SELECT lastSummary FROM conversations WHERE id=2"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            // 新列可写(编辑改写形象 → 版本化缓存键的面)
            sql.execSQL("UPDATE characters SET avatarVersion = 3 WHERE id = 1")
            assertEquals(3L, longScalar("SELECT avatarVersion FROM characters WHERE id=1"))
        } finally { db.close() }
    }
}
