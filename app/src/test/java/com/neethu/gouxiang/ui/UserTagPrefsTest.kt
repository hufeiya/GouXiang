package com.neethu.gouxiang.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ui.profile.UserGender
import com.neethu.gouxiang.ui.profile.UserTagCatalog
import com.neethu.gouxiang.ui.profile.UserTagPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户标签单测(2026-10-31):默认未完成态(首启必弹)、save 往返、重复
 * 确认覆盖(SharedPreferences StringSet 内部实例坑)、目录合法性(id 唯一/
 * 数量≥最少选择数/性别恰 2 项)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserTagPrefsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults are uncompleted and empty`() {
        val c = context()
        assertFalse(UserTagPrefs.isCompleted(c))
        assertNull(UserTagPrefs.gender(c))
        assertTrue(UserTagPrefs.tagIds(c).isEmpty())
    }

    @Test
    fun `save roundtrip marks completed`() {
        val c = context()
        UserTagPrefs.save(c, UserGender.FEMALE, setOf("campus", "urban", "ancient", "anime"))
        assertTrue(UserTagPrefs.isCompleted(c))
        assertEquals(UserGender.FEMALE, UserTagPrefs.gender(c))
        assertEquals(setOf("campus", "urban", "ancient", "anime"), UserTagPrefs.tagIds(c))
    }

    @Test
    fun `second save overwrites gender and tags`() {
        val c = context()
        UserTagPrefs.save(c, UserGender.MALE, setOf("campus", "urban", "ancient", "anime"))
        UserTagPrefs.save(c, UserGender.FEMALE, setOf("romance", "scifi", "fun", "simulator"))
        assertEquals(UserGender.FEMALE, UserTagPrefs.gender(c))
        assertEquals(setOf("romance", "scifi", "fun", "simulator"), UserTagPrefs.tagIds(c))
    }

    @Test
    fun `gender fromId roundtrip and unknown null`() {
        assertEquals(UserGender.MALE, UserGender.fromId("male"))
        assertEquals(UserGender.FEMALE, UserGender.fromId("female"))
        assertNull(UserGender.fromId(null))
        assertNull(UserGender.fromId("other"))
    }

    @Test
    fun `catalog ids are unique and complete`() {
        val ids = UserTagCatalog.TAGS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(UserTagCatalog.TAGS.size >= UserTagCatalog.MIN_SELECTED_TAGS)
        assertTrue(UserTagGenderCount.check())
        UserTagCatalog.TAGS.forEach { tag ->
            assertTrue(tag.label.isNotBlank())
            assertNotNull(UserTagCatalog.byId(tag.id))
        }
        assertNull(UserTagCatalog.byId("no_such_tag"))
    }
}

/** 性别恰 2 项(男生/女生,单选)。 */
private object UserTagGenderCount {
    fun check(): Boolean = UserGender.entries.size == 2 &&
        UserGender.entries.map { it.id }.toSet() == setOf("male", "female")
}
