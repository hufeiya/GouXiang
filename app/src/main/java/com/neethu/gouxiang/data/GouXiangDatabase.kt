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
        PendingMemoryTurnEntity::class,
        MemoryConversationVersion::class,
        DerivativeResultEntity::class,
        DerivativeTaskRecord::class,
        InnerVoiceEntity::class,
        MomentEntity::class,
        MomentCommentEntity::class,
        MomentInteractionEntity::class,
        ProactiveOutboxEntity::class,
        ProactiveConversationStateEntity::class,
        CharacterStatusEntity::class,
        SidestoryRecordEntity::class,
        ChatDerivativeEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class GouXiangDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryWindowDao(): MemoryWindowDao
    abstract fun derivativeResultDao(): DerivativeResultDao
    abstract fun derivativeTaskDao(): DerivativeTaskDao
    abstract fun innerVoiceDao(): InnerVoiceDao
    abstract fun momentDao(): MomentDao
    abstract fun momentCommentDao(): MomentCommentDao
    abstract fun momentInteractionDao(): MomentInteractionDao
    abstract fun proactiveDao(): ProactiveDao
    abstract fun characterStatusDao(): CharacterStatusDao
    abstract fun sidestoryRecordDao(): SidestoryRecordDao
    abstract fun chatDerivativeDao(): ChatDerivativeDao
    abstract fun seedDao(): SeedDao

    companion object {
        const val NAME = "gouxiang.db"

        /**
         * 心声升级迁移(v13→v14,单列纯增量):`inner_voices` 加 `content`
         * 心声正文短文列(既有行回填 '' = 详情信纸正文回落 echo 展示,旧数据
         * 可读不崩);其余列/表不动。
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inner_voices ADD COLUMN content TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * H 工作包迁移(v12→v13,纯增量:6 列 + 1 表):
         * - `moments` 加 `authorKind`(H1 作者模型,回填 "avatar" = 旧动态
         *   全部是角色帖);用户帖以 `characterId=0` + `authorKind='user'` 表达;
         * - `moment_comments` 加 `authorId`(H1 评论者模型);旧库单角色模型下
         *   avatar 评论都出自动态作者本人,按所属动态的 characterId 回填;
         * - `characters` 加 H2 朋友圈权限四列(参与/看用户动态/评论互动/
         *   角色级自动日更),回填 1 = 既有行为不变(默认全参与);
         * - 新建 `moment_interactions` 互评认领表(H3 防循环/防重复的幂等
         *   账本,(postId, characterId) 唯一)。
         * 既有动态/评论/点赞/图片列一律不动(H1「点赞/图片保持可读」)。
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN authorKind TEXT NOT NULL DEFAULT 'avatar'")
                db.execSQL("ALTER TABLE moment_comments ADD COLUMN authorId INTEGER NOT NULL DEFAULT 0")
                // 旧单角色模型:avatar 评论的作者 = 所属动态的作者角色(回填可读)
                db.execSQL(
                    "UPDATE moment_comments SET authorId = " +
                        "(SELECT moments.characterId FROM moments WHERE moments.id = moment_comments.postId) " +
                        "WHERE authorKind = 'avatar'",
                )
                db.execSQL("ALTER TABLE characters ADD COLUMN momentsJoined INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN momentsSeeUserPosts INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN momentsInteract INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN momentsDailyAuto INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `moment_interactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`postId` INTEGER NOT NULL, " +
                        "`characterId` INTEGER NOT NULL, " +
                        "`requestId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_moment_interactions_postId_characterId` " +
                        "ON `moment_interactions` (`postId`, `characterId`)",
                )
            }
        }

        /**
         * G 工作包迁移(v11→v12,单表纯增量 + 角色开关列):
         * - 新建聊天衍生内容记录表 `chat_derivative_records`(手帐/小说/合照
         *   三类共用,kind 分区;与番外 `sidestory_records` 同构不并表);
         * - `characters` 加 `innerVoiceAuto` 角色心声开关列(G4,既有行回填 1
         *   = 允许自动;心声自动全局开关默认关,叠加后行为不变)。
         * 既有角色/会话/消息/记忆/心声/朋友圈/状态/番外不动——尤其 inner_voices
         * 的存量心声记录在升级中原样保留(G4「迁移不丢失」)。
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        /**
         * F 工作包迁移(v10→v11,单表纯增量):新建统一内容记录表
         * `sidestory_records`(番外剧场模板产物与心动事件产物同表,kind 分区)。
         * 既有角色/会话/消息/记忆/心声/朋友圈/状态不动,存量安装无记录可迁,
         * 升级后从首个生成请求开始积累。
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        /**
         * E 工作包迁移(v9→v10,单表纯增量):新建角色状态记录表
         * `character_status_records`(E2 的心情/正在做/内心想法 + 来源范围 +
         * 会话版本 + 生效/失效状态机);既有角色/会话/消息/记忆不动,存量安装
         * 无状态记录,升级后从首个状态生成回合开始积累。
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        /**
         * D 工作包迁移(v8→v9,双表纯增量):新建主动消息账本两张表
         * `proactive_outbox`(批次行,requestId 唯一幂等)+ `proactive_conversation_state`
         * (每会话一行频控/未读状态);既有角色/会话/消息不动,存量会话按需
         * 懒建状态行(ensureState),无需数据迁移。
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        /**
         * C 工作包迁移(v7→v8,单列纯增量):`characters` 加 `avatarVersion`
         * 头像生成版本列(既有行回填 0 = 从未按版本重生成);其余表不动。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * M0 收尾迁移(v6→v7,单列纯增量):`derivative_tasks` 加 `failureKind`
         * 失败原因分类列(既有行回填 NULL,展示层回落「未知」);其余表不动。
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE derivative_tasks ADD COLUMN failureKind TEXT")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The old unowned payload remains untouched for explicit user review.
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
            }
        }


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
        if (mid < 1) return@mapNotNull null
        MemoryTurn(user = block.substring(1, mid), assistant = block.substring(mid + 1))
    }
}
