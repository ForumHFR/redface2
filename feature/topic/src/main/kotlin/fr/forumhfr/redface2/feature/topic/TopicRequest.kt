package fr.forumhfr.redface2.feature.topic

data class TopicRequest(
    val cat: Int,
    val post: Int,
    val page: Int,
    val scrollTo: Int?,
    /**
     * #231 — `true` when the topic is opened from a drapeau/flag (the user's intent is
     * to catch up on new posts). The cache-aside path still shows the cached page
     * instantly but **always** refreshes afterwards, bypassing the 60s snappy-cache TTL
     * that would otherwise serve a followed topic stale. Ordinary in-app navigation
     * leaves it `false` to keep back-nav snappy.
     *
     * ⚠️ #600 (vague 3) piggybacks on this flag as the « last read » discriminator
     * (`shouldShowLastReadMarker`): the flag tap is its ONLY producer today, and the only
     * navigation whose [scrollTo] means « last read post ». Adding another producer of
     * `forceRefresh=true` requires giving the marker its own dedicated field first.
     */
    val forceRefresh: Boolean = false,
    /**
     * Display-only fallback title for the top app bar while the ENTRY page is still loading (a
     * topic freshly opened from a list / flag / deep link starts in `Loading` with no topic yet),
     * which would otherwise flash the generic « Sujet » fallback. `:app` keeps a per-topic title
     * cache and feeds the last known title here so the bar stays stable. Never used once a page is
     * `Loaded` (the real `Topic.title` wins) — in-topic page changes stay inside the retained
     * ViewModel (#895 étape 4) and never come back through this hint.
     */
    val titleHint: String? = null,
    /**
     * #750/#1032 — `true` when [page] is NOT trusted to contain [scrollTo]: HFR inbound links can
     * carry an anchor while serialising `page=1` (email legacy form and pretty URLs). Before the
     * first load the ViewModel resolves the actual page through HFR's server-side redirect (same
     * probe as the search results, #277) and adopts it as the REAL target page — timeout / failure
     * falls back to [page], never worse than before. `false` on every in-app navigation (quote
     * taps and search results already carry a trusted page).
     */
    val resolveScrollToPage: Boolean = false,
    /**
     * #293 — entry-only moderation target, opened once after loading the page containing it.
     * Also supplies the entry scroll target when [scrollTo] is absent.
     */
    val moderationAlertFor: Int? = null,
)
