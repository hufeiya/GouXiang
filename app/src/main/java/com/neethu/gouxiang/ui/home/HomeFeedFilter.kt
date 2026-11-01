package com.neethu.gouxiang.ui.home

import com.neethu.gouxiang.data.CatalogCharacter
import com.neethu.gouxiang.data.HomeFeedCard
import com.neethu.gouxiang.ui.profile.UserGender

/** 目录人物即使已有会话，也必须使用目录里的受众筛选。 */
internal fun homeFeedItems(
    cards: List<HomeFeedCard>,
    catalog: List<CatalogCharacter>,
    userGender: UserGender?,
): List<FeedItem> {
    // 尚未选择时保留原有男性向推荐。旧目录 unknown 也属于男性向。
    val audience = (userGender ?: UserGender.MALE).id
    val matchingCatalog = catalog.filter {
        when (it.audience.trim().lowercase()) {
            "", "unknown" -> audience == "male"
            else -> it.audience.trim().lowercase() == audience
        }
    }
    val matchingSources = matchingCatalog.mapTo(mutableSetOf()) { "maoxiang:${it.sourceId}" }
    val matchingCards = cards.filter {
        if (it.character.source.startsWith("maoxiang:")) {
            it.character.source in matchingSources
        } else {
            // 既有本地人物没有 audience，按此前人物均为男性向处理。
            audience == "male"
        }
    }
    val localSources = matchingCards.mapTo(mutableSetOf()) { it.character.source }
    return matchingCards.map { FeedItem.Local(it.copy(openingLine = it.character.openingLine)) } +
        matchingCatalog.filterNot { "maoxiang:${it.sourceId}" in localSources }.map { FeedItem.Catalog(it) }
}
