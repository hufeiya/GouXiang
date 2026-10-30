package com.neethu.gouxiang.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 朋友圈作者类型(10-moments.md §1.9 authorKind;avatar=角色发帖 / user=用户发帖)。 */
object MomentAuthorKind {
    const val AVATAR = "avatar"
    const val USER = "user"
}

/**
 * 朋友圈动态表(F9→H 工作包 H1,10-moments.md §1.9 Post schema 的本地化子集:
 * postId→id / authorKind→authorKind(avatar=角色发帖 / user=用户发帖)/
 * text→text / images[0..1]→imageFile / createdAtMs→at / likedBy→likedByMe
 * 布尔坍缩——本地版只有一个「用户」可点赞):
 *
 * - [characterId] 语义按 [authorKind] 分派:avatar 帖 = 发帖角色的
 *   [CharacterEntity.id];user 帖 = 0(作者就是用户本人,无需归属角色);
 * - 配图为可选增强(10 §5 风险 3):文字先行落库,生图失败保持 imageFile 空
 *   (纯文字卡兜底),成功后经 setImageFile 原地回填(UI 走 observe 流自动上屏);
 * - 本地版无付费(范围 5):无 visibility/audienceIds/hidden/safetyState——
 *   锁卡/邀请字段不建模,UI 不得出现「待解锁/解锁」字样;用户帖的可见范围由
 *   各角色的 momentsSeeUserPosts 权限(H2)在查询/互评层执行,不做逐帖受众表。
 */
@Entity(
    tableName = "moments",
    indices = [Index("characterId")],
)
data class MomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** avatar 帖 = 发帖角色 id;user 帖恒 0(见类注释)。 */
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
    @ColumnInfo(defaultValue = "0") val isStale: Boolean = false,
    /**
     * 作者类型(H1;迁移回填 "avatar" = 旧单角色动态全部归角色)。
     */
    @ColumnInfo(defaultValue = "avatar") val authorKind: String = MomentAuthorKind.AVATAR,
)

/**
 * 朋友圈评论表(F9→H 工作包 H1,10-moments.md §1.9 Comment schema 的本地化子集:
 * commentId→id / authorId→authorId / replyToCommentId→replyToId 楼中楼——
 * 本地版一层,深度≤2):
 *
 * - [authorKind]:avatar = 角色经 LLM 生成的评论/回复;user = 用户手评;
 * - [authorId] 评论者模型(H1 多角色互评):avatar 评论 = 评论角色的
 *   [CharacterEntity.id];user 评论恒 0。旧库(v12 前)单角色模型下所有
 *   avatar 评论都出自动态作者本人,迁移按动态 characterId 回填(MIGRATION_12_13);
 * - 失败安全:角色回复生成失败只插失败占位提示行(kind=avatar、text=兜底
 *   文案),与聊天页「永远有回复」的兜底约定同源,界面永远不崩;互评引擎
 *   (H3)失败则认领释放、静默跳过,不留占位行。
 */
@Entity(
    tableName = "moment_comments",
    indices = [Index("postId")],
)
data class MomentCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属动态 [MomentEntity.id]。 */
    val postId: Long,
    /** "avatar" = 角色评论/回复 / "user" = 用户评论。 */
    val authorKind: String,
    /** 回复的目标评论 id(null = 直接评论,非楼中楼)。 */
    val replyToId: Long? = null,
    /** 评论文本。 */
    val text: String,
    /** 评论时间(epoch millis)。 */
    val at: Long = 0,
    /**
     * 评论者 id(H1):avatar 评论 = 评论角色 id;user 评论 = 0。
     */
    @ColumnInfo(defaultValue = "0") val authorId: Long = 0,
)

/**
 * 角色互评认领表(H3 防循环/防重复的幂等账本):
 *
 * - 每条动态一轮互评,候选角色评论前先认领([MomentInteractionDao.claim],
 *   (postId, characterId) 唯一索引 = 同角色对同动态至多成功一条互评);
 * - 认领即占位(state=running),生成成功后与评论行同事务置 succeeded;
 *   生成失败删除认领行(可重试,不重复、不无限循环);
 * - 评论本身不触发新一轮互评 → 结构上杜绝「楼中楼无限接力」;
 * - [requestId] 供任务历史/调试排查(非幂等键,幂等键是唯一索引二元组)。
 */
@Entity(
    tableName = "moment_interactions",
    indices = [Index(value = ["postId", "characterId"], unique = true)],
)
data class MomentInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属动态 [MomentEntity.id]。 */
    val postId: Long,
    /** 互评角色 [CharacterEntity.id]。 */
    val characterId: Long,
    /** 生成请求 id(追踪用)。 */
    val requestId: String,
    /** running = 生成中;succeeded = 已落评论(终态,唯一占位)。 */
    val state: String,
    /** 认领时间(epoch millis)。 */
    val at: Long = 0,
)

/** 互评认领状态(H3):running=生成中(失败即删行);succeeded=评论已落(终态)。 */
object MomentInteractionState {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
}
