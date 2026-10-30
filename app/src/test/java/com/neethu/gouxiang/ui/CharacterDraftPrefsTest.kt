package com.neethu.gouxiang.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ui.character.CharacterDraft
import com.neethu.gouxiang.ui.character.CharacterDraftPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C 工作包 C1:新建角色草稿的本地持久化(保存/恢复/清除/损坏兜底)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CharacterDraftPrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: CharacterDraftPrefs

    @Before
    fun setUp() {
        context.getSharedPreferences(CharacterDraftPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = CharacterDraftPrefs(context)
    }

    @Test
    fun draftRoundTripsAllFields() {
        val draft = CharacterDraft(
            name = "小满",
            profile = "设定",
            intro = "简介",
            exampleLines = "台词",
            openingLine = "(挥手)你好",
            appearance = "黑长直",
            appearanceStyle = "水墨风",
        )
        prefs.save(draft)
        assertEquals(draft, prefs.load())
    }

    @Test
    fun blankDraftIsNotPersisted() {
        prefs.save(CharacterDraft())
        assertNull(prefs.load())
        // 保存空白草稿会清掉既有草稿(用户清空全部字段 = 放弃草稿)
        prefs.save(CharacterDraft(name = "小满", profile = "p"))
        prefs.save(CharacterDraft())
        assertNull(prefs.load())
    }

    @Test
    fun corruptJsonFallsBackToNoDraft() {
        context.getSharedPreferences(CharacterDraftPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(CharacterDraftPrefs.KEY_DRAFT, "{not-json").commit()
        assertNull(prefs.load())
    }

    @Test
    fun clearRemovesDraft() {
        prefs.save(CharacterDraft(name = "小满", profile = "p", openingLine = "o"))
        prefs.clear()
        assertNull(prefs.load())
    }
}
