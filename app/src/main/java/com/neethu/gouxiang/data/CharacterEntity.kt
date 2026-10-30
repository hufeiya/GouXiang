package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 角色卡表(对齐 §6.2 猫箱事实 schema;字段用途差异遵循 01-character-card.md §1.2
 * 的核心结论:profile 进对话 prompt、用「用户」称呼对话者;intro 是公开展示文案、
 * 用「你」称呼;exampleLines 是 few-shot 演示;openingLine 是首条消息,() 描述动作)。
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 角色名(唯一键用途,列表/会话关联用)。 */
    val name: String,
    /**
     * 角色设定 = 核心 prompt:性格、身份、说话风格、与用户的关系。
     * 人设规范:用「用户」称呼对话者。
     */
    val profile: String,
    /**
     * 角色简介(公开展示,描述相遇场景/背景,用「你」称呼)。
     */
    val intro: String,
    /** 台词示例(选填,few-shot)。 */
    val exampleLines: String,
    /** 开场白:角色说的第一句话,可使用 () 描述动作或场景。 */
    val openingLine: String,
    /**
     * 形象描述(性别/发型/面部表情/肤色/服饰 + 风格词,喂生图模型)。
     */
    val appearance: String,
    /** 生图风格词缀(如「写实摄影风」「水墨风」)。 */
    val appearanceStyle: String,
    /** 生成头像缓存文件名(filesDir/avatars/ 下,空=用占位)。 */
    val avatarFile: String = "",
    /**
     * 头像生成版本(C 工作包 C3):版本化缓存键的一环 —— 缓存键 =
     * `character-<id>-v<avatarVersion>`,改写形象描述/风格并保存时 +1 且
     * [avatarFile] 置空,强制用新描述生成新文件,不复用旧头像。旧文件保留
     * 在图库(filesDir/avatars/)可经「设为当前」切回。
     */
    val avatarVersion: Long = 0,
    /**
     * 该角色是否允许自动生成心声(G4 角色开关,07-chat-derivatives.md §1.4
     * 「角色可生成 Ta 的心声」的本地化):只门控「自动」路径——心声自动批量
     * ([com.neethu.gouxiang.work.InnerVoiceAutoWorker] 入队与 [com.neethu.gouxiang.work.DerivativeWorker]
     * 执行时双重检查);点击「生成心声」不受限。默认开(与心声自动全局开关
     * 默认关叠加:关全局=谁都不自动,开全局=未关角色的可自动)。
     */
    @androidx.room.ColumnInfo(defaultValue = "1") val innerVoiceAuto: Boolean = true,
    /**
     * H2 朋友圈权限四开关(10-moments.md §1.6 三开关 + 本地自动日更的
     * 角色级拆分;默认全开,迁移回填 1 保住既有行为):
     *
     * - [momentsJoined] 角色是否参与朋友圈:关 = 不再出现在多角色时间线、
     *   不作为互评候选、自动日更不生成(调度入队与 Worker 执行双重检查);
     *   其历史动态仍可在角色主页「Ta 的朋友圈」单独查看(数据不删);
     * - [momentsSeeUserPosts] 能否看用户动态:关 = 看不到用户发帖(互评引擎
     *   不为其生成对用户帖的评论;合并时间线中用户帖对该角色的可见性同理);
     * - [momentsInteract] 能否评论互动:关 = 不再发任何评论/回复(含对自己
     *   动态下用户评论的回复),互评引擎不为其认领互评;
     * - [momentsDailyAuto] 按角色独立控制自动日更:关 = 该角色不自动发帖
     *   (与全局「朋友圈日更」开关叠加;点「生成动态」不受限)。
     */
    @androidx.room.ColumnInfo(defaultValue = "1") val momentsJoined: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val momentsSeeUserPosts: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val momentsInteract: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val momentsDailyAuto: Boolean = true,
    /** 来源标识(预留:脑洞 2.0 / 本地创作)。 */
    val source: String = "local",
    /** 创建时间(epoch millis)。 */
    val createdAt: Long,
)
