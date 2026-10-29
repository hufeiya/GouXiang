package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 朋友圈动态表(F9「Ta 的朋友圈」,对齐 10-moments.md §1.9 Post schema 的
 * 本地化子集:postId→id / authorKind→authorKind(本地单角色,恒 avatar)/
 * text→text / images[0..1]→imageFile / createdAtMs→at / likedBy→likedByMe
 * 布尔坍缩——本地版只有一个「用户」可点赞):
 *
 * - 配图为可选增强(10 §5 风险 3):文字先行落库,生图失败保持 imageFile 空
 *   (纯文字卡兜底),成功后经 setImageFile 原地回填(UI 走 observe 流自动上屏);
 * - 本地版无计费(范围 5):无 visibility/audienceIds/hidden/safetyState——
 *   锁卡/邀请/猫粮字段不建模,UI 不得出现「待解锁/解锁/猫粮」字样。
 */
@Entity(
    tableName = "moments",
    indices = [Index("characterId")],
)
data class MomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [CharacterEntity.id](按角色分片)。 */
    val characterId: Long,
    /** 动态正文(第一人称,贴合人设;( )动作描述允许)。 */
    val text: String,
    /** 配图描述(imagePrompt;生图喂料,空 = 纯文字动态)。 */
    val imagePrompt: String = "",
    /** 配图本地文件路径(空 = 纯文字卡 / 生图失败兜底)。 */
    val imageFile: String = "",
    /** 用户是否已点赞(本地单点赞者;点赞数 = 1:0)。 */
    val likedByMe: Boolean = false,
    /** 发布时间(epoch millis;列表按此倒序,「刚刚/N小时前」展示用)。 */
    val at: Long = 0,
)

/**
 * 朋友圈评论表(F9,10-moments.md §1.9 Comment schema 的本地化子集:
 * commentId→id / replyToCommentId→replyToId 楼中楼——本地版只有「角色回复
 * 用户评论」一层,深度≤2):
 *
 * - [authorKind]:avatar = 角色经 LLM 生成的回复;user = 用户手评;
 * - 失败安全:角色回复生成失败只插失败占位提示行(kind=avatar、text=兜底
 *   文案),与聊天页「永远有回复」的兜底约定同源,界面永远不崩。
 */
@Entity(
    tableName = "moment_comments",
    indices = [Index("postId")],
)
data class MomentCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属动态 [MomentEntity.id]。 */
    val postId: Long,
    /** "avatar" = 角色回复 / "user" = 用户评论。 */
    val authorKind: String,
    /** 回复的目标评论 id(null = 直接评论,非楼中楼)。 */
    val replyToId: Long? = null,
    /** 评论文本。 */
    val text: String,
    /** 评论时间(epoch millis)。 */
    val at: Long = 0,
)
