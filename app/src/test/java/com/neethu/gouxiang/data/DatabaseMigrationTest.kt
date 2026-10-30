package com.neethu.gouxiang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                GouXiangDatabase.MIGRATION_8_9,
                GouXiangDatabase.MIGRATION_9_10,
                GouXiangDatabase.MIGRATION_10_11,
                GouXiangDatabase.MIGRATION_11_12,
                GouXiangDatabase.MIGRATION_12_13,
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
            .addMigrations(GouXiangDatabase.MIGRATION_6_7, GouXiangDatabase.MIGRATION_7_8, GouXiangDatabase.MIGRATION_8_9, GouXiangDatabase.MIGRATION_9_10, GouXiangDatabase.MIGRATION_10_11, GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
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
            .addMigrations(GouXiangDatabase.MIGRATION_7_8, GouXiangDatabase.MIGRATION_8_9, GouXiangDatabase.MIGRATION_9_10, GouXiangDatabase.MIGRATION_10_11, GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
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

    /** v8 库 = v7 全部语句 + avatarVersion 列(照 7→8 真实迁移顺序)。 */
    private fun createV8FromV7() {
        createV7FromV6()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL("ALTER TABLE characters ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0")
        db.version = 8
        db.close()
    }

    /** D 工作包 v8→v9:新建主动消息账本两表,既有角色/会话/消息原样保留。 */
    @Test fun v8ProactiveLedgerTablesCreatedAndOldDataKept() {
        createV8FromV7()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_8_9, GouXiangDatabase.MIGRATION_9_10, GouXiangDatabase.MIGRATION_10_11, GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            // 既有数据原样保留
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals("摘要", scalar("SELECT lastSummary FROM conversations WHERE id=2"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            // 新表可写可读(账本预约 + 状态行的落地面)
            sql.execSQL(
                "INSERT INTO proactive_outbox (conversationId, characterId, batchId, requestId, kind, " +
                    "turnId, messageIds, count, state, failureReason, notified, seen, seenAt, createdAt, updatedAt) " +
                    "VALUES (2, 1, 'batch-1', 'req-1', 'DAILY_SHARE', '', '', 0, 'PENDING', '', 0, 0, 0, 10, 10)",
            )
            assertEquals("PENDING", scalar("SELECT state FROM proactive_outbox WHERE requestId='req-1'"))
            sql.execSQL(
                "INSERT INTO proactive_conversation_state (conversationId, characterId, lastUserActivityAt, " +
                    "lastProactiveAt, lastBatchAt, lastBatchCount, lastBatchMessageIds, unseenCount, lastSeenAt, " +
                    "dailyStamp, dailyCount, activityOverride) VALUES (2, 1, 3, 0, 0, 0, '', 0, 0, '', 0, 0)",
            )
            assertEquals(0L, sql.query("SELECT dailyCount FROM proactive_conversation_state WHERE conversationId=2").use { it.moveToFirst(); it.getLong(0) })
            // requestId 唯一索引生效(重复预约被拒)
            try {
                sql.execSQL(
                    "INSERT INTO proactive_outbox (conversationId, characterId, batchId, requestId, kind, " +
                        "turnId, messageIds, count, state, failureReason, notified, seen, seenAt, createdAt, updatedAt) " +
                        "VALUES (2, 1, 'batch-2', 'req-1', 'CHECK_IN', '', '', 0, 'PENDING', '', 0, 0, 0, 11, 11)",
                )
                throw AssertionError("requestId 唯一索引应拒绝重复插入")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                // 预期:唯一冲突
            }
        } finally { db.close() }
    }

    /** v9 库 = v8 全部语句 + 主动消息账本两表(照 8→9 真实迁移顺序)。 */
    private fun createV9FromV8() {
        createV8FromV7()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proactive_outbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`conversationId` INTEGER NOT NULL, " +
                "`characterId` INTEGER NOT NULL, " +
                "`batchId` TEXT NOT NULL, " +
                "`requestId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`turnId` TEXT NOT NULL, " +
                "`messageIds` TEXT NOT NULL, " +
                "`count` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`failureReason` TEXT NOT NULL, " +
                "`notified` INTEGER NOT NULL, " +
                "`seen` INTEGER NOT NULL, " +
                "`seenAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_outbox_conversationId` ON `proactive_outbox` (`conversationId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_proactive_outbox_requestId` ON `proactive_outbox` (`requestId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proactive_conversation_state` (" +
                "`conversationId` INTEGER NOT NULL, " +
                "`characterId` INTEGER NOT NULL, " +
                "`lastUserActivityAt` INTEGER NOT NULL, " +
                "`lastProactiveAt` INTEGER NOT NULL, " +
                "`lastBatchAt` INTEGER NOT NULL, " +
                "`lastBatchCount` INTEGER NOT NULL, " +
                "`lastBatchMessageIds` TEXT NOT NULL, " +
                "`unseenCount` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, " +
                "`dailyStamp` TEXT NOT NULL, " +
                "`dailyCount` INTEGER NOT NULL, " +
                "`activityOverride` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`conversationId`))",
        )
        db.version = 9
        db.close()
    }

    /** E 工作包 v9→v10:新建角色状态记录表,既有角色/会话/消息/账本原样保留。 */
    @Test fun v9CharacterStatusRecordsTableCreatedAndOldDataKept() {
        createV9FromV8()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_9_10, GouXiangDatabase.MIGRATION_10_11, GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            // 既有数据原样保留(角色/会话/消息 + v9 的账本行)
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            sql.execSQL(
                "INSERT INTO proactive_outbox (conversationId, characterId, batchId, requestId, kind, " +
                    "turnId, messageIds, count, state, failureReason, notified, seen, seenAt, createdAt, updatedAt) " +
                    "VALUES (2, 1, 'batch-1', 'req-e', 'DAILY_SHARE', '', '', 0, 'PENDING', '', 0, 0, 0, 10, 10)",
            )
            assertEquals("PENDING", scalar("SELECT state FROM proactive_outbox WHERE requestId='req-e'"))
            // 新表可写可读(状态记录落地面);来源区间/版本列语义成立
            sql.execSQL(
                "INSERT INTO character_status_records (characterId, conversationId, emoji, mood, doing, thought, " +
                    "sourceKind, sourceConversationId, sourceTurnId, sourceStartMessageId, sourceEndMessageId, " +
                    "sourceVersion, state, generatedAt) " +
                    "VALUES (1, 2, '🤔', '开心', '在等你的消息', '他在忙什么', 'turn', 2, 'turn-1', 3, 3, 0, 'active', 10)",
            )
            assertEquals("在等你的消息", scalar("SELECT doing FROM character_status_records WHERE characterId=1"))
            assertEquals("turn", scalar("SELECT sourceKind FROM character_status_records"))
            sql.execSQL("UPDATE character_status_records SET state='stale' WHERE conversationId=2 AND sourceEndMessageId >= 3")
            assertEquals("stale", scalar("SELECT state FROM character_status_records WHERE characterId=1"))
        } finally { db.close() }
    }

    /** v10 库 = v9 全部语句 + 角色状态记录表(照 9→10 真实迁移顺序)。 */
    private fun createV10FromV9() {
        createV9FromV8()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `character_status_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`characterId` INTEGER NOT NULL, " +
                "`conversationId` INTEGER NOT NULL, " +
                "`emoji` TEXT NOT NULL, " +
                "`mood` TEXT NOT NULL, " +
                "`doing` TEXT NOT NULL, " +
                "`thought` TEXT NOT NULL, " +
                "`sourceKind` TEXT NOT NULL, " +
                "`sourceConversationId` INTEGER, " +
                "`sourceTurnId` TEXT NOT NULL, " +
                "`sourceStartMessageId` INTEGER, " +
                "`sourceEndMessageId` INTEGER, " +
                "`sourceVersion` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`generatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_status_records_characterId` ON `character_status_records` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_status_records_conversationId` ON `character_status_records` (`conversationId`)")
        db.version = 10
        db.close()
    }

    /** F 工作包 v10→v11:新建统一内容记录表,既有角色/会话/消息/状态记录原样保留。 */
    @Test fun v10SidestoryRecordsTableCreatedAndOldDataKept() {
        createV10FromV9()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_10_11, GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            fun longScalar(query: String): Long = sql.query(query).use { it.moveToFirst(); it.getLong(0) }
            // 既有数据原样保留(角色/会话/消息 + v10 的状态记录行)
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals("聊天", scalar("SELECT content FROM messages WHERE id=3"))
            sql.execSQL(
                "INSERT INTO character_status_records (characterId, conversationId, emoji, mood, doing, thought, " +
                    "sourceKind, sourceConversationId, sourceTurnId, sourceStartMessageId, sourceEndMessageId, " +
                    "sourceVersion, state, generatedAt) " +
                    "VALUES (1, 2, '🤔', '开心', '在等你的消息', '他在忙什么', 'turn', 2, 'turn-1', 3, 3, 0, 'active', 10)",
            )
            assertEquals("在等你的消息", scalar("SELECT doing FROM character_status_records WHERE characterId=1"))
            // 新表可写可读(番外/心动记录落地面);类型分区与来源区间列语义成立
            sql.execSQL(
                "INSERT INTO sidestory_records (kind, templateKey, templateVersion, title, characterId, " +
                    "conversationId, sourceStartMessageId, sourceEndMessageId, sourceVersion, requestId, state, " +
                    "failureReason, bodyJson, imageFile, imageStatus, notified, isStale, createdAt, updatedAt) " +
                    "VALUES ('heartbeat', 'checkphone', 1, '', 1, 2, 3, 3, 0, 'req-f1', 'generating', '', '', '', " +
                    "'pending', 0, 0, 10, 10)",
            )
            assertEquals("heartbeat", scalar("SELECT kind FROM sidestory_records WHERE requestId='req-f1'"))
            assertEquals("checkphone", scalar("SELECT templateKey FROM sidestory_records WHERE requestId='req-f1'"))
            // 状态机条件更新语义成立(生成中→失败收口)
            sql.execSQL(
                "UPDATE sidestory_records SET state='failed', failureReason='生成失败' " +
                    "WHERE id=(SELECT id FROM sidestory_records WHERE requestId='req-f1') AND state='generating'",
            )
            assertEquals("failed", scalar("SELECT state FROM sidestory_records WHERE requestId='req-f1'"))
            assertEquals("生成失败", scalar("SELECT failureReason FROM sidestory_records WHERE requestId='req-f1'"))
            assertEquals(1L, longScalar("SELECT characterId FROM sidestory_records WHERE requestId='req-f1'"))
        } finally { db.close() }
    }

    /** v11 库 = v10 全部语句 + 番外记录表(照 10→11 真实迁移顺序)。 */
    private fun createV11FromV10() {
        createV10FromV9()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sidestory_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`templateKey` TEXT NOT NULL, " +
                "`templateVersion` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`characterId` INTEGER NOT NULL, " +
                "`conversationId` INTEGER NOT NULL, " +
                "`sourceStartMessageId` INTEGER, " +
                "`sourceEndMessageId` INTEGER, " +
                "`sourceVersion` INTEGER NOT NULL, " +
                "`requestId` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`failureReason` TEXT NOT NULL, " +
                "`bodyJson` TEXT NOT NULL, " +
                "`imageFile` TEXT NOT NULL, " +
                "`imageStatus` TEXT NOT NULL, " +
                "`notified` INTEGER NOT NULL, " +
                "`isStale` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sidestory_records_characterId` ON `sidestory_records` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sidestory_records_conversationId` ON `sidestory_records` (`conversationId`)")
        // 开发期已有的一条心声记录 + 一条番外记录:升级后必须原样保留(G4「迁移不丢失」)
        // (v5 种子已带 id=5 的心声行,这里用显式 id=7 避免冲突;两行都要活下来)
        db.execSQL("INSERT INTO inner_voices (id, characterId, title, echo, imageFile, at, isStale) VALUES (7, 1, '晚安心声', '今天也很想你', '', 9, 0)")
        db.execSQL(
            "INSERT INTO sidestory_records (kind, templateKey, templateVersion, title, characterId, conversationId, " +
                "sourceStartMessageId, sourceEndMessageId, sourceVersion, requestId, state, failureReason, bodyJson, " +
                "imageFile, imageStatus, notified, isStale, createdAt, updatedAt) " +
                "VALUES ('sidestory', 'Drunk', 1, '', 1, 2, 3, 3, 0, 'req-f0', 'succeeded', '', " +
                "'{\"title\":\"微醺\",\"pages\":[]}', '', 'skipped', 1, 0, 10, 10)",
        )
        db.version = 11
        db.close()
    }

    /** G 工作包 v11→v12:新建 chat_derivative_records + characters.innerVoiceAuto;心声/番外/角色原样保留。 */
    @Test fun v11ChatDerivativeRecordsCreatedAndInnerVoicesKept() {
        createV11FromV10()
        val db = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_11_12, GouXiangDatabase.MIGRATION_12_13)
            .allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            fun longScalar(query: String): Long = sql.query(query).use { it.moveToFirst(); it.getLong(0) }
            // G4 迁移不丢失:存量心声(v5 种子 id=5 与开发期新增 id=7)与番外记录原样保留
            assertEquals("心声", scalar("SELECT title FROM inner_voices WHERE id=5"))
            assertEquals("晚安心声", scalar("SELECT title FROM inner_voices WHERE id=7"))
            assertEquals("今天也很想你", scalar("SELECT echo FROM inner_voices WHERE id=7"))
            assertTrue(scalar("SELECT bodyJson FROM sidestory_records WHERE requestId='req-f0'").contains("微醺"))
            // 角色行保留,新开关列回填 1(允许自动;与全局自动默认关叠加后行为不变)
            assertEquals("角色", scalar("SELECT name FROM characters WHERE id=1"))
            assertEquals(1L, longScalar("SELECT innerVoiceAuto FROM characters WHERE id=1"))
            // 新表可写可读(手帐/小说/合照记录落地面);kind 分区 + 状态机语义成立
            sql.execSQL(
                "INSERT INTO chat_derivative_records (kind, title, characterId, conversationId, optionJson, " +
                    "sourceStartMessageId, sourceEndMessageId, sourceVersion, requestId, state, failureReason, " +
                    "bodyJson, imageFile, imageStatus, notified, isStale, createdAt, updatedAt) " +
                    "VALUES ('handbook', '', 1, 2, '{\"range\":\"turn10\"}', 3, 3, 0, 'req-g1', 'generating', '', " +
                    "'', '', 'pending', 0, 0, 10, 10)",
            )
            assertEquals("handbook", scalar("SELECT kind FROM chat_derivative_records WHERE requestId='req-g1'"))
            sql.execSQL(
                "UPDATE chat_derivative_records SET state='succeeded', title='放学后的天台' " +
                    "WHERE requestId='req-g1' AND state='generating'",
            )
            assertEquals("放学后的天台", scalar("SELECT title FROM chat_derivative_records WHERE requestId='req-g1'"))
            // 回溯失效联动语义成立(来源区间命中即标)
            sql.execSQL("UPDATE chat_derivative_records SET isStale = 1 WHERE conversationId = 2 AND sourceEndMessageId >= 3 AND isStale = 0")
            assertEquals(1L, longScalar("SELECT isStale FROM chat_derivative_records WHERE requestId='req-g1'"))
            // 角色开关可写(心声页 Switch 的落地面)
            sql.execSQL("UPDATE characters SET innerVoiceAuto = 0 WHERE id = 1")
            assertEquals(0L, longScalar("SELECT innerVoiceAuto FROM characters WHERE id=1"))
        } finally { db.close() }
    }

    /** v12 库 = v11 全部语句 + chat_derivative_records + innerVoiceAuto 列(照 11→12 真实迁移顺序)。 */
    private fun createV12FromV11() {
        createV11FromV10()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_derivative_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`characterId` INTEGER NOT NULL, " +
                "`conversationId` INTEGER NOT NULL, " +
                "`optionJson` TEXT NOT NULL, " +
                "`sourceStartMessageId` INTEGER, " +
                "`sourceEndMessageId` INTEGER, " +
                "`sourceVersion` INTEGER NOT NULL, " +
                "`requestId` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`failureReason` TEXT NOT NULL, " +
                "`bodyJson` TEXT NOT NULL, " +
                "`imageFile` TEXT NOT NULL, " +
                "`imageStatus` TEXT NOT NULL, " +
                "`notified` INTEGER NOT NULL, " +
                "`isStale` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_derivative_records_characterId` ON `chat_derivative_records` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_derivative_records_conversationId` ON `chat_derivative_records` (`conversationId`)")
        db.execSQL("ALTER TABLE characters ADD COLUMN innerVoiceAuto INTEGER NOT NULL DEFAULT 1")
        db.version = 12
        db.close()
    }

    /**
     * H 工作包 v12→v13:作者/评论者模型 + 权限四列 + 互评认领表;
     * 旧角色动态与点赞/评论(H1 验收 3)在 schema 变更后仍可见、可读。
     */
    @Test fun v13MomentsAuthorModelPermissionsAndOldDataKept() {
        createV12FromV11()
        // 旧单角色模型的存量数据:一条已点赞动态 + 一条角色评论(作者=动态作者)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "INSERT INTO moments (id, characterId, text, imagePrompt, imageFile, likedByMe, at, isStale) " +
                "VALUES (9, 1, '旧动态正文', '配图描述', '/tmp/moments/old.jpg', 1, 20, 0)",
        )
        db.execSQL(
            "INSERT INTO moment_comments (id, postId, authorKind, replyToId, text, at) " +
                "VALUES (10, 9, 'avatar', NULL, '旧角色评论', 21)",
        )
        db.execSQL(
            "INSERT INTO moment_comments (id, postId, authorKind, replyToId, text, at) " +
                "VALUES (11, 9, 'user', NULL, '旧用户评论', 22)",
        )
        db.version = 12
        db.close()

        val db2 = Room.databaseBuilder(context, GouXiangDatabase::class.java, name)
            .addMigrations(GouXiangDatabase.MIGRATION_12_13)
            .allowMainThreadQueries().build()
        try {
            val sql = db2.openHelper.writableDatabase
            fun scalar(query: String): String = sql.query(query).use { it.moveToFirst(); it.getString(0) }
            fun longScalar(query: String): Long = sql.query(query).use { it.moveToFirst(); it.getLong(0) }
            // H1:旧动态原样可见,authorKind 回填 avatar、点赞与配图路径保持可读
            assertEquals("旧动态正文", scalar("SELECT text FROM moments WHERE id=9"))
            assertEquals("avatar", scalar("SELECT authorKind FROM moments WHERE id=9"))
            assertEquals(1L, longScalar("SELECT likedByMe FROM moments WHERE id=9"))
            assertEquals("/tmp/moments/old.jpg", scalar("SELECT imageFile FROM moments WHERE id=9"))
            // H1 评论者模型:avatar 评论按动态作者回填 authorId=1;user 评论 authorId=0
            assertEquals(1L, longScalar("SELECT authorId FROM moment_comments WHERE id=10"))
            assertEquals("旧角色评论", scalar("SELECT text FROM moment_comments WHERE id=10"))
            assertEquals(0L, longScalar("SELECT authorId FROM moment_comments WHERE id=11"))
            // H2:权限四列回填 1(默认全参与,既有行为不变)
            assertEquals(1L, longScalar("SELECT momentsJoined FROM characters WHERE id=1"))
            assertEquals(1L, longScalar("SELECT momentsSeeUserPosts FROM characters WHERE id=1"))
            assertEquals(1L, longScalar("SELECT momentsInteract FROM characters WHERE id=1"))
            assertEquals(1L, longScalar("SELECT momentsDailyAuto FROM characters WHERE id=1"))
            // H3:互评认领表可写;(postId, characterId) 唯一索引拒绝重复认领
            sql.execSQL(
                "INSERT INTO moment_interactions (postId, characterId, requestId, state, at) " +
                    "VALUES (9, 1, 'req-h1', 'running', 30)",
            )
            assertEquals("running", scalar("SELECT state FROM moment_interactions WHERE postId=9 AND characterId=1"))
            try {
                sql.execSQL(
                    "INSERT INTO moment_interactions (postId, characterId, requestId, state, at) " +
                        "VALUES (9, 1, 'req-h2', 'running', 31)",
                )
                throw AssertionError("moment_interactions 唯一索引应拒绝同 (postId, characterId) 重复认领")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                // 预期:唯一冲突
            }
            // 权限列可写(H2 面板的落地面)
            sql.execSQL("UPDATE characters SET momentsJoined = 0 WHERE id = 1")
            assertEquals(0L, longScalar("SELECT momentsJoined FROM characters WHERE id=1"))
        } finally { db2.close() }
    }
}
