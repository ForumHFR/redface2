package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.forum.RestList
import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The captured global response contains category lists, not topics at its first level. */
@Serializable
internal data class RestGlobalFlagGroup(
    @SerialName("resources") val resources: List<RestTopic>,
    @SerialName("results_count") val resultsCount: Int,
    @SerialName("links") val links: RestGlobalFlagLinks,
)

@Serializable
internal data class RestGlobalFlagLinks(val category: RestGlobalFlagCategory)

@Serializable
internal data class RestGlobalFlagCategory(val href: String)

/**
 * Positive observations only: the global counter cannot prove completeness of any category.
 * Identical duplicates collapse into one observation; conflicting duplicates reject the batch.
 * An empty list is a successful no-op: it must not write rows, dates or a coverage marker.
 */
internal fun decodeGlobalFlags(json: Json, body: String, type: FlagType): List<Flag> {
    val root = json.parseToJsonElement(body) as? JsonObject
    val resource = root?.get("resource") as? JsonObject
    if (resource == null || "resources" !in resource || "results_count" !in resource) {
        throw SerializationException("Missing global flags list schema")
    }
    val groups = json.decodeFromString<RestListEnvelope<RestGlobalFlagGroup>>(body).resource.resources
    val flags = groups.flatMap { group ->
        val cat = globalCategoryId(group.links.category.href)
            ?: throw SerializationException("Invalid global flags category")
        validateGlobalGroup(group, cat)
        RestFlagMappers.toFlags(RestListEnvelope(RestList(resources = group.resources)), type, cat)
    }
    return distinctGlobalFlags(flags)
}

private fun validateGlobalGroup(group: RestGlobalFlagGroup, cat: Int) {
    if (group.resultsCount < 0) throw SerializationException("Invalid global flags count")
    group.resources.forEach { topic ->
        if (topic.isRead == null || !hasGlobalTopicIdentity(topic, cat)) {
            throw SerializationException("Invalid global flags topic identity")
        }
    }
}

private fun distinctGlobalFlags(flags: List<Flag>): List<Flag> {
    val groups = flags.groupBy { it.cat to it.topicId }
    if (groups.values.any { duplicates -> duplicates.any { it != duplicates.first() } }) {
        throw SerializationException("Conflicting global flags identities")
    }
    return groups.values.map { it.first() }
}

private fun hasGlobalTopicIdentity(topic: RestTopic, cat: Int): Boolean =
    topic.id > 0 && topic.links.category?.href?.let(::globalCategoryId) == cat && hasGlobalPostIdentity(topic, cat)

private fun hasGlobalPostIdentity(topic: RestTopic, cat: Int): Boolean {
    val identity = topic.links.posts?.href?.substringBefore('?')?.let(GLOBAL_POSTS::matchEntire) ?: return false
    return identity.groupValues[1].toIntOrNull() == cat &&
        identity.groupValues[3].toIntOrNull() == topic.id &&
        hasGlobalSubcategoryIdentity(topic, cat, identity.groupValues[2])
}

private fun hasGlobalSubcategoryIdentity(topic: RestTopic, cat: Int, postsSubcat: String): Boolean {
    val link = topic.links.subcategory ?: return true
    val identity = link.href?.let(GLOBAL_SUBCATEGORY::matchEntire)
    val subcat = identity?.groupValues?.get(2)?.toIntOrNull()
    return subcat != null && identity.groupValues[1].toIntOrNull() == cat &&
        (postsSubcat.isEmpty() || postsSubcat.toIntOrNull() == subcat)
}

private val GLOBAL_POSTS = Regex(
    "https://forum\\.hardware\\.fr/api/forums/hardwarefr/categories/([1-9][0-9]*)/" +
        "(?:subcategories/([1-9][0-9]*)/)?topics/([1-9][0-9]*)/posts/",
)
private val GLOBAL_SUBCATEGORY = Regex(
    "https://forum\\.hardware\\.fr/api/forums/hardwarefr/categories/([1-9][0-9]*)/subcategories/([1-9][0-9]*)/",
)
private val GLOBAL_CATEGORY = Regex("https://forum\\.hardware\\.fr/api/forums/hardwarefr/categories/([1-9][0-9]*)/")
private fun globalCategoryId(href: String): Int? = GLOBAL_CATEGORY.matchEntire(href)?.groupValues?.get(1)?.toIntOrNull()
