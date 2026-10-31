package com.neethu.gouxiang.ai.speech

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.neethu.gouxiang.data.CatalogCharacter
import com.neethu.gouxiang.data.CharacterEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CharacterVoiceStoreTest {
    private fun character(profile: String = "林川，男，24岁。", source: String = "local") = CharacterEntity(
        id = 91, name = "林川", profile = profile, intro = "", exampleLines = "", openingLine = "",
        appearance = "", appearanceStyle = "", source = source, createdAt = 0,
    )

    @Test fun explicitAttributesAssignSameGenderFallback() {
        val match = CharacterVoiceMatcher.match("林川", "林川，男，24岁。", "")
        assertEquals(VoiceGender.MALE, match.gender)
        assertEquals(VoiceAge.YOUNG_ADULT, match.age)
        assertTrue(match.voiceId!!.contains("zh_male_"))
        assertTrue(match.fallbackVoiceId!!.contains("zh_male_"))
    }

    @Test fun userAndOtherPeopleDoNotSupplyFacts() {
        val match = CharacterVoiceMatcher.match("林川", "林川是你的朋友。用户，女，22岁。她的母亲，女，40岁。", "")
        assertNull(match.voiceId)
        assertEquals(VoiceGender.UNKNOWN, match.gender)
        assertNull(CharacterVoiceMatcher.match("林川", "王林川，男，22岁。", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("林川", "林川，24岁时和用户相遇。林川，男。", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("林川", "林川，男，24岁时和用户相遇。", "").voiceId)
    }

    @Test fun conflictingAndUnsupportedAgesDoNotAssign() {
        assertNull(CharacterVoiceMatcher.match("林川", "林川，男，24岁。林川，男，42岁。", "").voiceId)
        assertEquals(VoiceAge.TEEN, CharacterVoiceMatcher.match("林川", "林川，男，14岁。", "").age)
        assertNull(CharacterVoiceMatcher.match("林川", "林川，男，8岁。", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("林川", "林川，男。", "").voiceId)
    }

    @Test fun namedBasicInfoIsScopedToItsOwnSection() {
        val profile = """
            ---何漪的基本信息---
            -性别：男
            -年龄：29
            -工作：观察员
            ---用户的基本信息---
            -性别：女
            -年龄：18
        """.trimIndent()
        val result = CharacterVoiceMatcher.match("何漪", profile, "")
        assertEquals(VoiceGender.MALE, result.gender)
        assertEquals(VoiceAge.YOUNG_ADULT, result.age)
        assertEquals("zh_male_ruyaqingnian_uranus_bigtts", result.voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---用户的基本信息---\n-性别：女\n-年龄：18", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪和用户的基本信息---\n-性别：男\n-年龄：29", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---小何漪的基本信息---\n-性别：男\n-年龄：29", "").voiceId)
    }

    @Test fun basicInfoRequiresUnambiguousCompleteFields() {
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪的基本信息---\n-性别：男\n---用户---\n-年龄：29", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪的基本信息---\n-性别：男\n-年龄：29\n-年龄：30", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪的基本信息---\n-性别：男\n-年龄：29\n-用户信息：\n-性别：女\n-年龄：18", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪的基本信息---\n-性别：男\n-年龄：29\n---何漪的基本信息---\n-性别：女\n-年龄：18", "").voiceId)
        assertNull(CharacterVoiceMatcher.match("何漪", "---何漪的基本信息---\n-性别：男\n-年龄：29\n何漪，女，29岁。", "").voiceId)
    }

    @Test fun catalogAndChatShareAssignmentAndManualSurvivesCatalogReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("character_voice_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = CharacterVoiceStore(context)
        fun catalog(profile: String) = CatalogCharacter("123", "林川", "", "", profile, "visible", "", "", "")
        store.assignCatalog(listOf(catalog("林川，男，24岁。")))
        val chat = character(source = "maoxiang:123")
        assertNotNull(store.getOrAssign(chat).voiceId)
        store.setManual(chat, "custom_voice")
        store.assignCatalog(listOf(catalog("林川，女，25岁。")))
        assertEquals("custom_voice", CharacterVoiceStore(context).getOrAssign(chat).voiceId)
        assertTrue(store.getOrAssign(chat).isManual)
        assertFalse(store.reset(chat).isManual)
        assertEquals(VoiceGender.MALE, store.getOrAssign(chat).gender)
        store.setManual(chat, "zh_female_popo_uranus_bigtts")
        assertEquals("ICL_uranus_zh_female_heainainai_tob", store.getOrAssign(chat).fallbackVoiceId)
    }
}
