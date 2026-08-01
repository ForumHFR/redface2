package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor

/**
 * #307 — composite cache key for the per-page scroll-anchor cache. Same `(cat, post)` rationale as
 * [TopicTitleKey] (a topic id is unique only per HFR category), extended with the page number: the
 * read position is a property of ONE page of the topic, and returning to page N must never inherit
 * the anchor of page M.
 */
internal data class TopicScrollKey(val cat: Int, val post: Int, val page: Int)

// Upper bound on the per-page scroll-anchor cache, mirroring TOPIC_TITLE_CACHE_MAX (PR #338): a long
// reading session touches many pages; the cap keeps the map from growing unbounded for the app's
// lifetime. Eviction drops the least-recently-WRITTEN entries — losing an old anchor just lands that
// page back at the top, which is the pre-#307 behaviour.
internal const val TOPIC_SCROLL_ANCHOR_CACHE_MAX = 128

/**
 * Inserts [anchor] for [key] into the per-page scroll-anchor cache, evicting the oldest entries past
 * [TOPIC_SCROLL_ANCHOR_CACHE_MAX]. Twin of [withTitle], with one deliberate divergence: an update to
 * an existing key is remove-then-reinsert, NOT a plain `Map + pair`. `LinkedHashMap.put` keeps an
 * existing key at its ORIGINAL insertion rank, so the plain `+` would evict a page visited early but
 * still actively read — the entry with the FRESHEST anchor — before 128 pages it has not seen since.
 * Re-inserting at the tail makes eviction least-recently-written: the cheap approximation of LRU
 * that fits an immutable map (reads never refresh the rank, every save does). The unchanged-value
 * short-circuit avoids allocating a fresh map — and recomposing `RedfaceApp` — when a departure
 * reports the exact position already cached.
 */
internal fun Map<TopicScrollKey, TopicScrollAnchor>.withScrollAnchor(
    key: TopicScrollKey,
    anchor: TopicScrollAnchor,
): Map<TopicScrollKey, TopicScrollAnchor> {
    if (this[key] == anchor) return this
    val updated = (if (containsKey(key)) this - key else this) + (key to anchor)
    return if (updated.size > TOPIC_SCROLL_ANCHOR_CACHE_MAX) {
        updated.entries.drop(updated.size - TOPIC_SCROLL_ANCHOR_CACHE_MAX).associate { it.toPair() }
    } else {
        updated
    }
}

/**
 * #307 — what the topic screen's initial scroll should do for one route landing. Resolved ONCE per
 * landing by [resolveTopicScrollRestoration]; only [RestoreSaved] makes the screen move the list —
 * the two `Follow*` levels mean « a higher-priority mechanism owns the scroll, stay out of its way ».
 */
internal sealed interface TopicScrollRestoration {
    /**
     * The route carries a `scrollTo` (deep link, search, drapeau, quote/edit submit): the existing
     * `ScrollToPost` effect owns the landing. Always wins.
     */
    data object FollowScrollTo : TopicScrollRestoration

    /**
     * Post-submit route (`submitSignal != null`) without a `scrollTo`: `TopicViewModel.maybeEmitScroll`
     * emits `ScrollToEndOfPage` (#200) — including on the #226/#344 overflow landing
     * (`postSubmitOverflowLanding`, paired with a fresh `submitSignal` by the nav host). Restoring a
     * stale anchor here would fight the « show my fresh post » scroll, so the saved position is
     * deliberately ignored.
     */
    data object FollowSubmitLanding : TopicScrollRestoration

    /** A position was saved for this exact `(cat, post, page)` — restore it once loaded. */
    data class RestoreSaved(val anchor: TopicScrollAnchor) : TopicScrollRestoration

    /**
     * #412 — « page précédente » navigation onto a page with no saved anchor: land at the BOTTOM.
     * Reading a thread backwards, the posts that chronologically follow what the user just read
     * are the LAST ones of the previous page (same landing HFR's own `#bas` anchor gives on the
     * web). A saved anchor still wins — this only replaces the [StartAtTop] fallback.
     */
    data object StartAtBottom : TopicScrollRestoration

    /** Never-visited page (or evicted anchor): keep the default top-of-page landing. */
    data object StartAtTop : TopicScrollRestoration
}

/**
 * #307 — pure priority resolver for the initial scroll of a topic page landing. STRICT order:
 *
 *  1. route `scrollTo` (deep link / search / drapeau / quote-edit submit) →
 *     [TopicScrollRestoration.FollowScrollTo] ;
 *  2. post-submit route (`submitSignal != null`, covers the #344 `ScrollToEndOfPage` path and the
 *     #226 overflow landing) → [TopicScrollRestoration.FollowSubmitLanding] ;
 *  3. quote-jump return anchor (#782, `jumpReturnAnchor` — the position captured at the tap that
 *     started the jump, handed back by the back interception) → [TopicScrollRestoration.RestoreSaved].
 *     ABOVE the saved anchor on purpose: an intra-page jump overwrites the disposal-saved anchor of
 *     the same `(cat, post, page)` with the CITED post's position, so on a return the tap-time
 *     capture is the only truthful one. A return route never carries `scrollTo`/`submitSignal`, so
 *     in practice this level owns its landing ;
 *  4. saved anchor for the page → [TopicScrollRestoration.RestoreSaved] ;
 *  5. « page précédente » navigation (#412, `previousPageLanding`) →
 *     [TopicScrollRestoration.StartAtBottom] ;
 *  6. nothing → [TopicScrollRestoration.StartAtTop].
 *
 * Pure so the six levels are unit-testable without Compose/nav3 (cf. `TopicScrollRestorationTest`).
 */
internal fun resolveTopicScrollRestoration(
    scrollTo: Int?,
    submitSignal: Long?,
    savedAnchor: TopicScrollAnchor?,
    previousPageLanding: Boolean = false,
    jumpReturnAnchor: TopicScrollAnchor? = null,
): TopicScrollRestoration = when {
    scrollTo != null -> TopicScrollRestoration.FollowScrollTo
    submitSignal != null -> TopicScrollRestoration.FollowSubmitLanding
    jumpReturnAnchor != null -> TopicScrollRestoration.RestoreSaved(jumpReturnAnchor)
    savedAnchor != null -> TopicScrollRestoration.RestoreSaved(savedAnchor)
    previousPageLanding -> TopicScrollRestoration.StartAtBottom
    else -> TopicScrollRestoration.StartAtTop
}
