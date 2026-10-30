package com.neethu.gouxiang.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 心声卡表(F8「Ta 的心声」,对齐 07-chat-derivatives.md §1.4 MemoryCardDetail
 * 的本地化子集:card_name→title / card_echo→echo / image_url→imageFile /
 * create_time→at;本地版无付费与时效——锁态/过期/重roll/领取等字段不建模,
 * 卡片只显示生成日期,范围 3 红线:「剩余 N 天过期」不出现)。
 *
 * 一次「生成心声」= LLM 产出 2~4 条 → 各落一行(同批 at 依次错开,倒序列表下
 * 首条心声置顶);配图为可选增强:生图失败保持 imageFile 空(纯文字卡兜底),
 * 成功后经 setImageFile 原地回填(UI 走 observe 流自动上屏)。
 */
@Entity(
    tableName = "inner_voices",
    indices = [Index("characterId")],
)
data class InnerVoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [CharacterEntity.id](按角色分片)。 */
    val characterId: Long,
    /** 心声小标题(卡片第一行,dump:tv_title)。 */
    val title: String,
    /** 心声正文(第一人称内心短句,dump:tv_desc / card_echo)。 */
    val echo: String,
    /** 配图本地文件路径(空 = 纯文字卡)。 */
    val imageFile: String = "",
    /** 生成时间(epoch millis;列表按此倒序,日期行展示用)。 */
    val at: Long = 0,
    @ColumnInfo(defaultValue = "0") val isStale: Boolean = false,
)
