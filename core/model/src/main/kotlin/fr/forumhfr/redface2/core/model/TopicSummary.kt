package fr.forumhfr.redface2.core.model

/**
 * One row in a list of topics — what the user sees on a category screen, before
 * opening the actual topic to read posts. Distinct from [Topic] which carries the
 * page of [Post]s for a single thread.
 *
 * Sourced from the REST endpoint
 * `categories/{cat}/[subcategories/{sub}/]topics/last/?page=N&results_per_page=M`.
 */
data class TopicSummary(
    val cat: Int,
    val subcat: Int?,
    val topicId: Int,
    val title: String,
    val author: String,
    val lastReplyAuthor: String,
    /** Raw timestamp string from REST (`YYYY-MM-DD HH:mm`). Parsing is deferred. */
    val lastReplyAt: String,
    /** Number of replies (= total posts − 1). Derived from `links.posts.count`. */
    val replyCount: Int,
    /**
     * `ceil(posts.count / postsResultsPerPage)` where `postsResultsPerPage` is
     * read from `links.posts.href?results_per_page=N` on the REST topic payload.
     * 40 is **not** assumed globally — the HTML reader exposes a per-user
     * setting; only the REST href advertises a stable bucket.
     */
    val totalPages: Int,
    val isSticky: Boolean,
    val isLocked: Boolean,
    /**
     * Authenticated-only field. `true` when REST `is_read = false`, `false` when
     * `is_read = true`, `null` when the JSON omits the field (anonymous response).
     */
    val hasUnread: Boolean?,
    /**
     * Authenticated-only field. Page index (1-based) of the last post the user
     * read for this topic, parsed from `links.posts.href?page=N`. **Not** REST's
     * `last_position`, which is the per-post offset inside that page, not a page
     * number. `null` on anonymous payloads or when the auth href omits `page`.
     */
    val lastReadPage: Int?,
    /**
     * Authenticated-only field, mirrors REST `last_post_read_id` — the id of
     * the last post the user actually read (anchor for a "scroll to last read"
     * deep link). Distinct from "first unread", which is one post further.
     */
    val lastPostReadId: Int?,
)

/**
 * A single page of [TopicSummary] for a category (or subcategory). Pagination
 * metadata is preserved so the UI can build its own page indicator without
 * re-querying the server.
 */
data class TopicListPage(
    val cat: Int,
    val subcat: Int?,
    val page: Int,
    val resultsPerPage: Int,
    val totalTopics: Int,
    val topics: List<TopicSummary>,
)
