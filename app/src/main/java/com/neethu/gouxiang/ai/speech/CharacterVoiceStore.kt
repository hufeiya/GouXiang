package com.neethu.gouxiang.ai.speech

import android.content.Context
import com.neethu.gouxiang.data.CatalogCharacter
import com.neethu.gouxiang.data.CharacterEntity
import java.security.MessageDigest

/** 不确定的信息保留 UNKNOWN；年龄只是为了选择相近声线，并非人物资料。 */
enum class VoiceGender { MALE, FEMALE, UNKNOWN }
enum class VoiceAge { TEEN, YOUNG_ADULT, MIDDLE_AGED, SENIOR, UNKNOWN }

data class CharacterVoiceAssignment(
    val voiceId: String?,
    val fallbackVoiceId: String?,
    val gender: VoiceGender,
    val age: VoiceAge,
    val isManual: Boolean = false,
)

/** 仅采信与名字紧邻的明确描述，不从「你」、配角、称谓或开场白推测。 */
object CharacterVoiceMatcher {
    fun match(name: String, profile: String, intro: String): CharacterVoiceAssignment {
        if (name.isBlank()) return unknown()
        val text = listOf(profile, intro).distinct().joinToString("\n")
        val escaped = "(?<![\\p{IsHan}A-Za-z0-9])" + Regex.escape(name.trim())
        // Each fact must be an immediate attribute of the named character. No cross-sentence inference.
        val agePattern = Regex("$escaped\\s*[，,:： ]+\\s*(?:年龄\\s*[：:]\\s*)?(\\d{1,3})\\s*岁(?=[，,。；;\\s]|$)")
        val genderPattern = Regex("$escaped\\s*[，,:： ]+\\s*(?:性别\\s*[：:]\\s*)?(男性|女性|男|女)(?=[，,。；;\\s]|$)")
        val combinedAgeFirst = Regex("$escaped\\s*[，,:： ]+\\s*(\\d{1,3})\\s*岁\\s*[，,、 ]+\\s*(男性|女性|男|女)(?=[，,。；;\\s]|$)")
        val combinedGenderFirst = Regex("$escaped\\s*[，,:： ]+\\s*(男性|女性|男|女)\\s*[，,、 ]+\\s*(\\d{1,3})\\s*岁(?=[，,。；;\\s]|$)")
        val ages = agePattern.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toMutableSet()
        val genders = genderPattern.findAll(text).map { gender(it.groupValues[1]) }.toMutableSet()
        when (val section = basicInfoSection(name.trim(), text)) {
            is BasicInfo.Found -> { ages.add(section.age); genders.add(section.gender) }
            BasicInfo.Ambiguous -> return unknown()
            BasicInfo.Absent -> Unit
        }
        combinedAgeFirst.findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let(ages::add)
            genders.add(gender(it.groupValues[2]))
        }
        combinedGenderFirst.findAll(text).forEach {
            genders.add(gender(it.groupValues[1]))
            it.groupValues[2].toIntOrNull()?.let(ages::add)
        }
        val sex = genders.singleOrNull() ?: VoiceGender.UNKNOWN
        val age = when (ages.singleOrNull()) {
            in 13..17 -> VoiceAge.TEEN
            in 18..35 -> VoiceAge.YOUNG_ADULT
            in 36..59 -> VoiceAge.MIDDLE_AGED
            in 60..120 -> VoiceAge.SENIOR
            else -> VoiceAge.UNKNOWN
        }
        val choices = VoiceCandidates.forProfile(sex, age)
        return CharacterVoiceAssignment(choices.firstOrNull(), choices.getOrNull(1), sex, age)
    }

    private sealed interface BasicInfo {
        data object Absent : BasicInfo
        data object Ambiguous : BasicInfo
        data class Found(val age: Int, val gender: VoiceGender) : BasicInfo
    }

    /**
     * 仅识别独立的“---人物全名的基本信息---”段落，连续的性别/年龄字段必须
     * 出现在其他描述字段之前。遇到后续同名字段时放弃，防止把嵌套人物或用户字段
     * 与主人物混在一起。下一段标题之后的字段绝不参与匹配。
     */
    private fun basicInfoSection(name: String, text: String): BasicInfo {
        val lines = text.lines()
        val header = Regex("^[ \t]*-{3,}[ \t]*" + Regex.escape(name) + "的基本信息[ \t]*-{3,}[ \t]*$")
        val sections = lines.indices.filter { header.matches(lines[it]) }
        if (sections.isEmpty()) return BasicInfo.Absent
        if (sections.size != 1) return BasicInfo.Ambiguous
        val nextHeader = Regex("^[ \t]*(?:-{3,}.+?-{3,}|#{1,6}[ \t]+.+|【[^】]+】)[ \t]*$")
        val rawField = Regex("^[ \t]*[-*•]?[ \t]*(性别|年龄)[ \t]*[：:][ \t]*(.*?)[ \t]*$")
        val genders = mutableSetOf<VoiceGender>()
        val ages = mutableSetOf<Int>()
        var otherFieldSeen = false
        for (line in lines.drop(sections.single() + 1)) {
            if (nextHeader.matches(line)) break
            if (line.isBlank()) continue
            val field = rawField.matchEntire(line)
            if (field == null) {
                otherFieldSeen = true
                continue
            }
            if (otherFieldSeen) return BasicInfo.Ambiguous
            when (field.groupValues[1]) {
                "性别" -> when (field.groupValues[2]) {
                    "男", "男性" -> genders.add(VoiceGender.MALE)
                    "女", "女性" -> genders.add(VoiceGender.FEMALE)
                    else -> return BasicInfo.Ambiguous
                }
                "年龄" -> {
                    val value = Regex("^(\\d{1,3})岁?$").matchEntire(field.groupValues[2])
                        ?.groupValues?.get(1)?.toIntOrNull() ?: return BasicInfo.Ambiguous
                    ages.add(value)
                }
            }
        }
        return if (genders.size == 1 && ages.size == 1) BasicInfo.Found(ages.single(), genders.single())
        else BasicInfo.Ambiguous
    }

    private fun gender(value: String) = if (value.startsWith("男")) VoiceGender.MALE else VoiceGender.FEMALE
    private fun unknown() = CharacterVoiceAssignment(null, null, VoiceGender.UNKNOWN, VoiceAge.UNKNOWN)
}

/** 候选来自火山豆包语音 2.0 官方音色表；名称只代表近似听感，需用户试听确认。 */
object VoiceCandidates {
    private val choices = mapOf(
        (VoiceGender.MALE to VoiceAge.TEEN) to listOf("ICL_uranus_zh_male_qingshuangshaonian_tob", "ICL_uranus_zh_male_wennuanshaonian_tob"),
        (VoiceGender.FEMALE to VoiceAge.TEEN) to listOf("ICL_uranus_zh_female_chunzhenshaonv_tob", "ICL_uranus_zh_female_qingxinshaonv_tob"),
        (VoiceGender.MALE to VoiceAge.YOUNG_ADULT) to listOf("zh_male_ruyaqingnian_uranus_bigtts", "zh_male_fanjuanqingnian_uranus_bigtts"),
        (VoiceGender.FEMALE to VoiceAge.YOUNG_ADULT) to listOf("zh_female_wenroushunv_uranus_bigtts", "zh_female_roumeinvyou_uranus_bigtts"),
        (VoiceGender.MALE to VoiceAge.MIDDLE_AGED) to listOf("zh_male_baqiqingshu_uranus_bigtts", "zh_male_yuanboxiaoshu_uranus_bigtts"),
        (VoiceGender.FEMALE to VoiceAge.MIDDLE_AGED) to listOf("zh_female_wenroumama_uranus_bigtts", "ICL_uranus_zh_female_linjuayi_tob"),
        (VoiceGender.MALE to VoiceAge.SENIOR) to listOf("ICL_uranus_zh_male_youmodaye_tob"),
        (VoiceGender.FEMALE to VoiceAge.SENIOR) to listOf("zh_female_popo_uranus_bigtts", "ICL_uranus_zh_female_heainainai_tob"),
    )

    fun forProfile(gender: VoiceGender, age: VoiceAge): List<String> = choices[gender to age].orEmpty()

    /** Only voices with explicitly curated peers receive a fallback. */
    fun forManual(voiceId: String): CharacterVoiceAssignment {
        val entry = choices.entries.firstOrNull { voiceId in it.value }
            ?: return CharacterVoiceAssignment(voiceId, null, VoiceGender.UNKNOWN, VoiceAge.UNKNOWN, true)
        return CharacterVoiceAssignment(voiceId, entry.value.firstOrNull { it != voiceId }, entry.key.first, entry.key.second, true)
    }
}

/**
 * 用来源 ID 连接大厅与已开始聊天的人物；其他人物用本地 ID。
 * 每次目录加载可安全补全新人物，数据变动仅重算自动结果，不覆盖用户选择。
 */
class CharacterVoiceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("character_voice_v1", Context.MODE_PRIVATE)

    /** Notify visible screens when another entry point changes this character's assignment. */
    fun observe(character: CharacterEntity, onChange: () -> Unit): () -> Unit {
        val prefix = "${key(character)}."
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey?.startsWith(prefix) == true) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getOrAssign(character: CharacterEntity): CharacterVoiceAssignment = getOrAssign(
        key(character), character.name, character.profile, character.intro,
    )

    fun getOrAssign(character: CatalogCharacter): CharacterVoiceAssignment = getOrAssign(
        "catalog:${character.sourceId}", character.name, character.profile.ifBlank { character.intro }, character.intro,
    )

    fun assignCatalog(characters: List<CatalogCharacter>) {
        characters.forEach { getOrAssign(it) }
    }

    fun setManual(character: CharacterEntity, voiceId: String) {
        require(voiceId.isNotBlank())
        val key = key(character)
        // These properties describe the selected voice and its fallback, not verified biographical facts.
        val choice = VoiceCandidates.forManual(voiceId.trim())
        prefs.edit().putString("$key.voice", voiceId.trim()).putString("$key.fallback", choice.fallbackVoiceId.orEmpty())
            .putString("$key.gender", choice.gender.name).putString("$key.age", choice.age.name)
            .putBoolean("$key.manual", true).putString("$key.signature", "manual").apply()
    }

    fun reset(character: CharacterEntity): CharacterVoiceAssignment {
        prefs.edit().remove("${key(character)}.signature").putBoolean("${key(character)}.manual", false).apply()
        return getOrAssign(character)
    }

    private fun getOrAssign(key: String, name: String, profile: String, intro: String): CharacterVoiceAssignment {
        val signature = signature(name, profile, intro)
        val manual = prefs.getBoolean("$key.manual", false)
        if (manual || prefs.getString("$key.signature", null) == signature) return read(key, manual)
        val assignment = CharacterVoiceMatcher.match(name, profile, intro)
        prefs.edit().putString("$key.voice", assignment.voiceId.orEmpty())
            .putString("$key.fallback", assignment.fallbackVoiceId.orEmpty())
            .putString("$key.gender", assignment.gender.name).putString("$key.age", assignment.age.name)
            .putBoolean("$key.manual", false).putString("$key.signature", signature).apply()
        return assignment
    }

    private fun read(key: String, manual: Boolean) = CharacterVoiceAssignment(
        prefs.getString("$key.voice", "").orEmpty().ifBlank { null },
        prefs.getString("$key.fallback", "").orEmpty().ifBlank { null },
        runCatching { VoiceGender.valueOf(prefs.getString("$key.gender", "UNKNOWN") ?: "UNKNOWN") }.getOrDefault(VoiceGender.UNKNOWN),
        runCatching { VoiceAge.valueOf(prefs.getString("$key.age", "UNKNOWN") ?: "UNKNOWN") }.getOrDefault(VoiceAge.UNKNOWN),
        manual,
    )

    private fun key(character: CharacterEntity): String {
        val source = character.source.removePrefix("maoxiang:")
        return if (character.source.startsWith("maoxiang:") && source.matches(Regex("[0-9]+"))) "catalog:$source"
        else "character:${character.id}"
    }

    private fun signature(vararg fields: String): String = MessageDigest.getInstance("SHA-256")
        .digest((listOf("matcher-v3") + fields).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
