package com.neethu.gouxiang.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地版猫箱数据底座(角色卡/会话/消息/记忆 + 待提炼窗口 + 心声卡 + 朋友圈)。
 */
@Database(
    entities = [
        CharacterEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        PendingMemoryWindowEntity::class,
        InnerVoiceEntity::class,
        MomentEntity::class,
        MomentCommentEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class GouXiangDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryWindowDao(): MemoryWindowDao
    abstract fun innerVoiceDao(): InnerVoiceDao
    abstract fun momentDao(): MomentDao
    abstract fun momentCommentDao(): MomentCommentDao
    abstract fun seedDao(): SeedDao

    companion object {
        const val NAME = "gouxiang.db"

        /**
         * F3 记忆系统迁移(v1→v2,单列+单表纯增量):
         * - memories 加 `subtype` 列(画像细类,既有行回填 '');
         * - 新建 pending_memory_window 单行表(待提炼窗口持久化)。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN subtype TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_memory_window` (" +
                        "`id` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * F8 心声迁移(v2→v3,单表纯增量,照 v1→v2 CREATE TABLE 先例):
         * 新建 inner_voices 表(心声卡本地持久化,既有安装无行可迁)。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inner_voices` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`characterId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`echo` TEXT NOT NULL, " +
                        "`imageFile` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inner_voices_characterId` ON `inner_voices` (`characterId`)")
            }
        }

        /**
         * F9 朋友圈迁移(v3→v4,双表纯增量,照 v2→v3 CREATE TABLE 先例):
         * 新建 moments / moment_comments 表(动态与评论本地持久化,既有安装
         * 无行可迁)。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `moments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`characterId` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`imagePrompt` TEXT NOT NULL, " +
                        "`imageFile` TEXT NOT NULL, " +
                        "`likedByMe` INTEGER NOT NULL, " +
                        "`at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_moments_characterId` ON `moments` (`characterId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `moment_comments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`postId` INTEGER NOT NULL, " +
                        "`authorKind` TEXT NOT NULL, " +
                        "`replyToId` INTEGER, " +
                        "`text` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_comments_postId` ON `moment_comments` (`postId`)")
            }
        }

        /**
         * 种子画像分类迁移(v4→v5,纯 UPDATE,照既有先例):
         * 旧版种子把「我的称呼」行错归 relationship、「我的身份」行漏了 subtype,
         * 画像区(MemoryBook.profile 只认 profile+subtype)读不到。存量安装经
         * seedIfAbsent 不会重播种子,按内容前缀就地修正(与 SeedData 新文案前缀一致)。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE memories SET category='profile', subtype='name' " +
                        "WHERE category='relationship' AND content LIKE '我的称呼%'",
                )
                db.execSQL(
                    "UPDATE memories SET subtype='identity' " +
                        "WHERE category='profile' AND subtype='' AND content LIKE '我的身份%'",
                )
            }
        }
    }
}

/** 窗口序列化:turn 分隔(\u0002),节内 user|assistant 以 \u0001 分隔。 */
internal fun serializeWindow(turns: List<MemoryTurn>): String =
    turns.joinToString("\u0002") { "\u0001${it.user}\u0001${it.assistant}" }

/** 窗口反序列化(与 [serializeWindow] 配对;坏节静默跳过)。 */
internal fun deserializeWindow(payload: String): List<MemoryTurn> {
    if (payload.isEmpty()) return emptyList()
    return payload.split('\u0002').mapNotNull { block ->
        if (block.length < 2 || !block.startsWith("\u0001")) return@mapNotNull null
        val mid = block.indexOf('\u0001', 1)
        if (mid <= 1) return@mapNotNull null
        MemoryTurn(user = block.substring(1, mid), assistant = block.substring(mid + 1))
    }
}
