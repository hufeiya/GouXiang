package com.neethu.gouxiang.ui.home

import com.neethu.gouxiang.data.CatalogCharacter
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.HomeFeedCard
import com.neethu.gouxiang.ui.profile.UserGender
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFeedFilterTest {
    private fun catalog(id: String, audience: String) = CatalogCharacter(
        sourceId = id, name = id, intro = "", openingLine = "", profile = "",
        profileStatus = "", creatorName = "", coverAsset = "", portraitAsset = "", audience = audience,
    )

    private fun card(source: String) = HomeFeedCard(
        character = CharacterEntity(
            name = source, profile = "", intro = "", exampleLines = "", openingLine = "",
            appearance = "", appearanceStyle = "", source = source, createdAt = 0,
        ),
        conversationId = 1, openingLine = "",
    )

    private val catalog = listOf(catalog("1", "male"), catalog("2", "female"), catalog("3", "unknown"))
    private val cards = listOf(card("maoxiang:1"), card("maoxiang:2"), card("local"))

    @Test fun `male feed keeps male and legacy entries and deduplicates chats`() {
        val items = homeFeedItems(cards, catalog, UserGender.MALE)
        assertEquals(listOf("maoxiang:1", "local"), items.filterIsInstance<FeedItem.Local>().map { it.card.character.source })
        assertEquals(listOf("3"), items.filterIsInstance<FeedItem.Catalog>().map { it.character.sourceId })
        assertEquals(items, homeFeedItems(cards, catalog, null))
    }

    @Test fun `female feed filters existing chats as well as catalog entries`() {
        val items = homeFeedItems(cards, catalog, UserGender.FEMALE)
        assertEquals(listOf("maoxiang:2"), items.filterIsInstance<FeedItem.Local>().map { it.card.character.source })
        assertEquals(emptyList<String>(), items.filterIsInstance<FeedItem.Catalog>().map { it.character.sourceId })
        assertEquals(listOf("2"), homeFeedItems(emptyList(), catalog, UserGender.FEMALE)
            .filterIsInstance<FeedItem.Catalog>().map { it.character.sourceId })
    }

    @Test fun `catalog chats wait for catalog before appearing`() {
        assertEquals(listOf("local"), homeFeedItems(cards, emptyList(), UserGender.MALE)
            .filterIsInstance<FeedItem.Local>().map { it.card.character.source })
    }
}
