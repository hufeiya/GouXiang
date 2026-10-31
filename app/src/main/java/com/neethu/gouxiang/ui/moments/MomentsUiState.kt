package com.neethu.gouxiang.ui.moments

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentFeed

/**
 * 朋友圈页 06(F9→H 工作包 H1/H2)的 UI 状态。
 *
 * [items] 为多角色合并时间线(用户帖 + 参与角色的动态,最新在上,observe 流
 * 驱动,生成/发帖落库即上屏;查询层权限过滤在 [MomentFeed.assemble]);
 * [comments] 按动态 id 分片的评论线程(动态卡下方展开显示,同一 observe 流);
 * [characterNames] 角色评论者展示名(avatar 评论按 authorId 归名,user=「我」);
 * [permissions] 权限管理面板行(H2:逐角色的四个开关快照);
 * [generating] 驱动生成中的提示;[publishing] 驱动「发动态」
 * 进行中态;[replyingToId] = 评论/回复生成进行中的动态(输入区防重入);
 * [error] = 上一次生成失败的提示文案(生成链失败安全,只提示不崩溃;
 * 下一次成功清空);[characterId] = 0 时为全局朋友圈入口(无当前角色)。
 */
@Immutable
data class MomentsUiState(
    val loading: Boolean = false,
    val generating: Boolean = false,
    val publishing: Boolean = false,
    /** 当前角色 id(0 = 全局朋友圈入口,H1)。 */
    val characterId: Long = 0,
    val characterName: String = "",
    val items: List<MomentFeed.MomentFeedItem> = emptyList(),
    val imageResults: Map<Long, com.neethu.gouxiang.ui.ImageGenerationState> = emptyMap(),
    val comments: Map<Long, List<MomentCommentEntity>> = emptyMap(),
    /** avatar 评论者/作者名(id→name;user 恒「我」)。 */
    val characterNames: Map<Long, String> = emptyMap(),
    /** 权限管理面板(H2)的逐角色开关快照。 */
    val permissions: List<MomentPermissionRow> = emptyList(),
    val permissionsLoading: Boolean = false,
    val replyingToId: Long? = null,
    val error: String? = null,
    val automaticError: String? = null,
    /** 加载失败(会话/角色不存在)。 */
    val loadFailed: Boolean = false,
)

/** H2 权限面板的开关字段(切换目标;映射到 characters 对应列)。 */
enum class MomentPermissionField {
    /** 参与朋友圈。 */
    JOINED,
    /** 看用户动态。 */
    SEE_USER_POSTS,
    /** 评论互动。 */
    INTERACT,
    /** 是否为角色发现动态。 */
    DAILY_AUTO,
}

/**
 * 权限管理面板行(H2):一个角色一行,四个开关快照由 VM 从角色行快照;
 * 切换即写回 `characters` 对应列(生成/查询层双执行在 Worker/引擎侧)。
 */
@Immutable
data class MomentPermissionRow(
    val characterId: Long,
    val name: String,
    /** 参与朋友圈(合并时间线可见、互评候选及动态发现前提)。 */
    val joined: Boolean,
    /** 看用户动态(用户帖的可见性/互评资格)。 */
    val seeUserPosts: Boolean,
    /** 评论互动(能否发任何评论/回复)。 */
    val interact: Boolean,
    /** 是否为角色发现动态。 */
    val dailyAuto: Boolean,
) {
    companion object {
        fun of(character: CharacterEntity) = MomentPermissionRow(
            characterId = character.id,
            name = character.name,
            joined = character.momentsJoined,
            seeUserPosts = character.momentsSeeUserPosts,
            interact = character.momentsInteract,
            dailyAuto = character.momentsDailyAuto,
        )
    }
}
