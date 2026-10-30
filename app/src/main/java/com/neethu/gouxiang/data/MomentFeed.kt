package com.neethu.gouxiang.data

/**
 * 多角色合并时间线(H1)的纯函数装配:用户帖 + 参与朋友圈角色的动态,按
 * 发布时间倒序。查询层权限过滤的唯一权威落点(与生成层 Worker/Scheduler
 * 的双重执行对应,路线图 H2):
 *
 * - avatar 帖:作者 [CharacterEntity.momentsJoined] = false → 不进合并流
 *   (退出朋友圈;历史动态仍可经角色主页分片查询单独查看,数据不删);
 * - user 帖:恒进合并流(用户自己的内容;哪些角色能「看」它由各角色的
 *   [CharacterEntity.momentsSeeUserPosts] 在互评层执行,不逐帖建模受众);
 * - 作者行不存在(角色已删)的动态不进合并流(不猜测归属)。
 */
object MomentFeed {

    /** 合并时间线行:动态 + 作者展示信息。 */
    data class MomentFeedItem(
        val post: MomentEntity,
        /** 展示名:user 帖 = "我";avatar 帖 = 角色名(缺名时回退占位)。 */
        val authorName: String,
        /** avatar 帖的作者角色 id(user 帖 = 0;头像/权限查询用)。 */
        val authorCharacterId: Long,
    ) {
        val isUserPost: Boolean get() = post.authorKind == MomentAuthorKind.USER
    }

    /** 用户帖展示名(评论区用户行的名字同源)。 */
    const val USER_DISPLAY_NAME = "我"

    /**
     * 装配合并时间线([posts] 需已按 at 倒序,原序保留;纯函数供单测)。
     */
    fun assemble(posts: List<MomentEntity>, charactersById: Map<Long, CharacterEntity>): List<MomentFeedItem> =
        posts.mapNotNull { post ->
            when (post.authorKind) {
                MomentAuthorKind.USER -> MomentFeedItem(post, USER_DISPLAY_NAME, 0L)
                else -> charactersById[post.characterId]
                    ?.takeIf { it.momentsJoined }
                    ?.let { MomentFeedItem(post, it.name, it.id) }
            }
        }
}
