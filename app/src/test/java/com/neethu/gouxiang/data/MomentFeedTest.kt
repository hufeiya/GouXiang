package com.neethu.gouxiang.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H 工作包 H1:多角色合并时间线的纯函数装配(查询层权限过滤的唯一权威
 * 落点;不参与角色的动态不进合并流,用户帖恒可见,孤儿动态不猜测归属)。
 */
class MomentFeedTest {

    private fun character(id: Long, name: String, joined: Boolean = true) = CharacterEntity(
        id = id, name = name, profile = "", intro = "", exampleLines = "",
        openingLine = "", appearance = "", appearanceStyle = "", createdAt = 0,
        momentsJoined = joined,
    )

    private fun post(id: Long, characterId: Long, kind: String, at: Long) = MomentEntity(
        id = id, characterId = characterId, authorKind = kind, text = "动态$id", at = at,
    )

    @Test
    fun assembleKeepsUserPostsAndJoinedAvatarsInTimeOrder() {
        val characters = mapOf(1L to character(1, "甲"), 2L to character(2, "乙"))
        val feed = MomentFeed.assemble(
            listOf(
                post(3, 0, MomentAuthorKind.USER, at = 30),
                post(2, 2, MomentAuthorKind.AVATAR, at = 20),
                post(1, 1, MomentAuthorKind.AVATAR, at = 10),
            ),
            characters,
        )
        // 原序保留(调用方传时间倒序),作者名/类型/角色 id 就位
        assertEquals(listOf(3L, 2L, 1L), feed.map { it.post.id })
        assertEquals("我", feed[0].authorName)
        assertTrue(feed[0].isUserPost)
        assertEquals(0L, feed[0].authorCharacterId)
        assertEquals("乙", feed[1].authorName)
        assertEquals("甲", feed[2].authorName)
    }

    @Test
    fun assembleDropsNonJoinedAvatarsAndOrphanPostsKeepsUserPosts() {
        val characters = mapOf(1L to character(1, "甲", joined = false), 2L to character(2, "乙"))
        val feed = MomentFeed.assemble(
            listOf(
                post(4, 0, MomentAuthorKind.USER, at = 40),   // 用户帖:恒可见
                post(3, 9, MomentAuthorKind.AVATAR, at = 30), // 作者已删:不进流、不猜测归属
                post(2, 1, MomentAuthorKind.AVATAR, at = 20), // 不参与朋友圈:不进合并流
                post(1, 2, MomentAuthorKind.AVATAR, at = 10),
            ),
            characters,
        )
        assertEquals(listOf(4L, 1L), feed.map { it.post.id })
    }

    @Test
    fun userDisplayConstantFeedsCommentNaming() {
        // 评论区用户行与用户帖作者名同源(纯常量,防两处硬编码漂移)
        assertEquals("我", MomentFeed.USER_DISPLAY_NAME)
        assertEquals(MomentAuthorKind.USER, "user")
        assertEquals(MomentAuthorKind.AVATAR, "avatar")
    }
}
