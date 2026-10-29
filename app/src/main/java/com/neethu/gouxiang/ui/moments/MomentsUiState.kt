package com.neethu.gouxiang.ui.moments

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity

/**
 * 朋友圈页 06(F9)的 UI 状态。
 *
 * [items] 为倒序动态卡(最新在上,observe 流驱动,生成落库即上屏);
 * [comments] 按动态 id 分片的评论线程(动态卡下方展开显示,同一 observe 流);
 * [generating] 驱动「生成动态」按钮的进行中态;[replyingToId] = 角色回复
 * 生成进行中的动态(输入区防重入);[error] = 上一次生成失败的提示文案
 * (生成链失败安全,只提示不崩溃;下一次成功清空)。
 */
@Immutable
data class MomentsUiState(
    val loading: Boolean = false,
    val generating: Boolean = false,
    /** 关联角色 id(MainActivity 经 avatarFiles 查头像缓存路径)。 */
    val characterId: Long = 0,
    val characterName: String = "",
    val items: List<MomentEntity> = emptyList(),
    val comments: Map<Long, List<MomentCommentEntity>> = emptyMap(),
    val replyingToId: Long? = null,
    val error: String? = null,
    /** 加载失败(会话/角色不存在)。 */
    val loadFailed: Boolean = false,
)
