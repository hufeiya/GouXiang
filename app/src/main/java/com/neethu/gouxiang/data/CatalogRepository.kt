package com.neethu.gouxiang.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An offline catalog entry. Browsing does not create a character or a conversation. */
data class CatalogCharacter(
    val sourceId: String,
    val name: String,
    val intro: String,
    val openingLine: String,
    val profile: String,
    val profileStatus: String,
    val creatorName: String,
    val coverAsset: String,
    val portraitAsset: String,
    val exampleLines: String = "",
    val audience: String = "unknown",
)

object CatalogRepository {
    /** Call from a background dispatcher. Empty when local sample assets were not supplied. */
    fun load(context: Context): List<CatalogCharacter> {
        val json = try {
            context.assets.open("catalog/characters.json").bufferedReader().use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        }
        return parse(json)
    }

    internal fun parse(json: String): List<CatalogCharacter> {
        val rows = JSONArray(json)
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.string("sourceId")
            val name = row.string("name")
            if (!id.matches(Regex("[0-9]+")) || name.isBlank()) return@mapNotNull null
            CatalogCharacter(
                sourceId = id,
                name = name,
                intro = row.string("intro"),
                openingLine = row.string("openingLine"),
                profile = row.string("rawSettingsText"),
                profileStatus = row.string("profileStatus"),
                creatorName = row.string("creatorName"),
                coverAsset = "catalog/images/$id/cover.webp",
                portraitAsset = "catalog/images/$id/portrait.webp",
                exampleLines = row.string("rawStyleText"),
                audience = row.string("audience").trim().lowercase().ifBlank { "unknown" },
            )
        }.distinctBy { it.sourceId }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key, "")
}

/** Starts or resumes a catalog character's chat, keyed by source ID and atomic in Room. */
suspend fun GouXiangRepo.startCatalogChat(context: Context, character: CatalogCharacter): SavedCharacter =
    withContext(Dispatchers.IO) {
        require(character.sourceId.matches(Regex("[0-9]+")))
        val filename = "catalog-${character.sourceId}.webp"
        val avatar = File(File(context.filesDir, "avatars"), filename)
        if (!avatar.isFile) {
            avatar.parentFile?.mkdirs()
            try {
                context.assets.open(character.coverAsset).use { input -> avatar.outputStream().use { input.copyTo(it) } }
            } catch (_: java.io.IOException) {
                avatar.delete()
            }
        }
        val now = System.currentTimeMillis()
        val profile = character.profile.ifBlank { character.intro }
        db.seedDao().startCatalogChat(
            character = CharacterEntity(
                name = character.name,
                profile = profile,
                intro = character.intro,
                exampleLines = character.exampleLines,
                openingLine = character.openingLine,
                appearance = "",
                appearanceStyle = "",
                avatarFile = if (avatar.isFile) filename else "",
                source = "maoxiang:${character.sourceId}",
                createdAt = now,
            ),
            openingAt = now,
        )
    }
