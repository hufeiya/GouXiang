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
    /** 来源标识(预留:脑洞 2.0 / 本地创作)。 */
    val source: String = "local",
    /** 创建时间(epoch millis)。 */
    val createdAt: Long,
)
