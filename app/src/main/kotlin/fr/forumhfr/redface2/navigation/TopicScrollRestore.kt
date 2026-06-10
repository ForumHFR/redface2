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
// lifetime. Eviction is FIFO (oldest insertions dropped) — losing an old anchor just lands that page
// back at the top, which is the pre-#307 behaviour.
internal const val TOPIC_SCROLL_ANCHOR_CACHE_MAX = 128

/**
 * Inserts [anchor] for [key] into the per-page scroll-anchor cache, evicting the oldest entries past
 * [TOPIC_SCROLL_ANCHOR_CACHE_MAX]. Twin of [withTitle]: `Map + pair` preserves insertion order
 * (LinkedHashMap), so dropping from the front evicts the least-recently-inserted anchors; updating an
 * existing key keeps its original insertion rank (stable FIFO). The unchanged-value short-circuit
 * avoids allocating a fresh map — and recomposing `RedfaceApp` — when a departure reports the exact
 * position already cached.
 */
internal fun Map<TopicScrollKey, TopicScrollAnchor>.withScrollAnchor(
    key: TopicScrollKey,
    anchor: TopicScrollAnchor,
): Map<TopicScrollKey, TopicScrollAnchor> {
    if (this[key] == anchor) return this
    val updated = this + (key to anchor)
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
 *  3. saved anchor for the page → [TopicScrollRestoration.RestoreSaved] ;
 *  4. nothing → [TopicScrollRestoration.StartAtTop].
 *
 * Pure so the four levels are unit-testable without Compose/nav3 (cf. `TopicScrollRestorationTest`).
 */
internal fun resolveTopicScrollRestoration(
    scrollTo: Int?,
    submitSignal: Long?,
    savedAnchor: TopicScrollAnchor?,
): TopicScrollRestoration = when {
    scrollTo != null -> TopicScrollRestoration.FollowScrollTo
    submitSignal != null -> TopicScrollRestoration.FollowSubmitLanding
    savedAnchor != null -> TopicScrollRestoration.RestoreSaved(savedAnchor)
    else -> TopicScrollRestoration.StartAtTop
}
