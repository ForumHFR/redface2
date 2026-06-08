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
    /**
     * #231 — `true` when the topic is opened from a drapeau/flag (the user's intent is
     * to catch up on new posts). The cache-aside path still shows the cached page
     * instantly but **always** refreshes afterwards, bypassing the 60s snappy-cache TTL
     * that would otherwise serve a followed topic stale. Ordinary in-app navigation
     * leaves it `false` to keep back-nav snappy.
     */
    val forceRefresh: Boolean = false,
    /**
     * Display-only fallback title for the top app bar while a freshly-navigated page is still
     * loading. A page change replaces the `TopicRoute` (new nav entry → new ViewModel → `Loading`
     * with no topic yet), which would otherwise flash the generic « Sujet » fallback. `:app` keeps
     * a per-topic title cache and feeds the last known title here so the bar stays stable. Never
     * used once the page is `Loaded` (the real `Topic.title` wins).
     */
    val titleHint: String? = null,
)
