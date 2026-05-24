package fr.forumhfr.redface2.feature.topic

data class TopicRequest(
    val cat: Int,
    val post: Int,
    val page: Int,
    val scrollTo: Int?,
    /**
     * Issue #200 — non-null when the topic screen is being reopened after an editor
     * submit (reply / quote / edit / edit-FP). The ViewModel bypasses the cache-aside
     * path and force-fetches the page so the freshly-published post is visible. The
     * value itself is a `System.currentTimeMillis()` timestamp from the navigation
     * host — only its presence/absence matters, not its magnitude.
     */
    val submitSignal: Long? = null,
)
