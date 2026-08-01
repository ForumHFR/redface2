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
 * #307 — what the topic screen's initial ENTRY scroll should do. Resolved ONCE per topic entry by
 * [resolveTopicScrollRestoration]; only [RestoreSaved] makes the screen move the list — the
 * `FollowScrollTo` level means « a higher-priority mechanism owns the landing, stay out of its way ».
 *
 * #895 étape 4 (PR 2) — this resolver now covers the ENTRY landing only. The historical mid-topic
 * levels died with the route-replace navigation: post-submit landings ride
 * `TopicViewModel.applySubmitResult`, quote-jump returns ride the in-VM jump chain, and the #412
 * « page précédente » bottom landing is armed by `TopicViewModel.switchToPage` — all delivered as
 * page-scoped scroll effects by the in-VM page engine.
 */
internal sealed interface TopicScrollRestoration {
    /**
     * The route carries a `scrollTo` (deep link, search, drapeau): the `ScrollToPost` effect owns
     * the landing. Always wins.
     */
    data object FollowScrollTo : TopicScrollRestoration

    /** A position was saved for this exact `(cat, post, page)` — restore it once loaded. */
    data class RestoreSaved(val anchor: TopicScrollAnchor) : TopicScrollRestoration

    /** Never-visited page (or evicted anchor): keep the default top-of-page landing. */
    data object StartAtTop : TopicScrollRestoration
}

/**
 * #307 — pure priority resolver for the initial scroll of a topic ENTRY landing. STRICT order:
 *
 *  1. route `scrollTo` (deep link / search / drapeau) → [TopicScrollRestoration.FollowScrollTo] ;
 *  2. saved anchor for the entry page → [TopicScrollRestoration.RestoreSaved] ;
 *  3. nothing → [TopicScrollRestoration.StartAtTop].
 *
 * Pure so the levels are unit-testable without Compose/nav3 (cf. `TopicScrollRestorationTest`).
 */
internal fun resolveTopicScrollRestoration(
    scrollTo: Int?,
    savedAnchor: TopicScrollAnchor?,
): TopicScrollRestoration = when {
    scrollTo != null -> TopicScrollRestoration.FollowScrollTo
    savedAnchor != null -> TopicScrollRestoration.RestoreSaved(savedAnchor)
    else -> TopicScrollRestoration.StartAtTop
}
