package fr.forumhfr.redface2.core.data.forum

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Single-resource envelope used by metadata-style REST responses. Captured fixtures:
 * `rest_topic_meta_35395.json` is the canonical example.
 */
@Serializable
internal data class RestSingleEnvelope<T>(
    @SerialName("resource") val resource: T,
)

/**
 * List envelope used by the paginated REST endpoints. The wrapper carries pagination
 * metadata (`page`, `results_count`, `results_per_page`) that the upstream consumers
 * need for `TopicListPage`. Subcategory and category list responses share the same
 * shape with `results_per_page == results_count` (no pagination), so we keep the
 * wrapper for them too.
 */
@Serializable
internal data class RestListEnvelope<T>(
    @SerialName("resource") val resource: RestList<T>,
)

@Serializable
internal data class RestList<T>(
    @SerialName("page") val page: Int = 1,
    @SerialName("results_count") val resultsCount: Int = 0,
    @SerialName("results_per_page") val resultsPerPage: Int = 0,
    @SerialName("resources") val resources: List<T> = emptyList(),
)

@Serializable
internal data class RestCategory(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("force_subcat") val forceSubcat: Boolean = false,
    @SerialName("number_of_subcategories") val numberOfSubcategories: Int = 0,
    // We deliberately ignore the `links.*` block on the category list payload: the
    // paths we care about (subcategories, last_topics, ...) are deterministic given
    // the category id, and consuming `links.*` would force a HATEOAS rewrite for
    // every category up front, which is wasted work for the public list.
)

@Serializable
internal data class RestSubcategory(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("links") val links: RestSubcategoryLinks? = null,
)

@Serializable
internal data class RestSubcategoryLinks(
    @SerialName("category") val category: RestLink? = null,
)

@Serializable
internal data class RestTopic(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("last_post_date") val lastPostDate: String = "",
    @SerialName("is_closed") val isClosed: Boolean = false,
    @SerialName("is_sticky") val isSticky: Boolean = false,
    @SerialName("links") val links: RestTopicLinks = RestTopicLinks(),
    // Authenticated-only fields. Default-null so anonymous fixtures stay round-trippable.
    @SerialName("is_read") val isRead: Boolean? = null,
    @SerialName("flag_owntopic") val flagOwntopic: Int? = null,
    @SerialName("last_position") val lastPosition: Int? = null,
    @SerialName("last_post_read_id") val lastPostReadId: Int? = null,
)

@Serializable
internal data class RestTopicLinks(
    @SerialName("category") val category: RestLink? = null,
    @SerialName("subcategory") val subcategory: RestLink? = null,
    @SerialName("posts") val posts: RestLink? = null,
    @SerialName("author") val author: RestUserLink? = null,
    @SerialName("last_author") val lastAuthor: RestUserLink? = null,
)

@Serializable
internal data class RestLink(
    @SerialName("href") val href: String? = null,
    @SerialName("count") val count: Int? = null,
)

@Serializable
internal data class RestUserLink(
    @SerialName("title") val title: String? = null,
    @SerialName("href") val href: String? = null,
)
