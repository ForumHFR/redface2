package fr.forumhfr.redface2.feature.topic

/**
 * #895 étape 4 (PR 2, gate r1) — tracks the page the shared `LazyListState` is actually ALIGNED
 * with : content AND scroll position both belong to [alignedPage].
 *
 * With the in-VM page engine, a switch swaps the list CONTENT (state update — instantaneous on an
 * LRU snapshot) before its landing effect moves the scroll POSITION. Inside that window the list
 * shows the new page's content at the OLD page's offset, and the canonical page already points to
 * the new page — so a late fling settle (or the screen's disposal save) would record the OLD
 * page's coordinates under the NEW page. The alignment marker closes that window :
 *
 *  - [onLandingApplied] is called ONLY once a landing (entry restore, `ScrollToAnchor` /
 *    `ScrollToEndOfPage` / `ScrollToTop` / `ScrollToPost`) has been applied for a page — from
 *    that point the position genuinely describes that page ;
 *  - [shouldPersist] gates every position persist (scroll-settle report, disposal save, tap-time
 *    departure anchors) : nothing is recorded while the list is not aligned with the CURRENT
 *    canonical page. A skipped persist just falls back to the engine's previous anchor for that
 *    page — never a corrupted one.
 *
 * Plain class (not Compose state) : consulted from effect lambdas only, never drives composition.
 */
internal class TopicListAlignment {

    /** Page the list content + position currently describe, or `null` before the first landing. */
    var alignedPage: Int? = null
        private set

    /** A landing for [page] was applied to the list — content and position now agree on it. */
    fun onLandingApplied(page: Int) {
        alignedPage = page
    }

    /**
     * `true` when a position read from the list can be persisted for [canonicalPage] : the page
     * is loaded AND the list is aligned with it (no pending landing of a fresher switch).
     */
    fun shouldPersist(canonicalPage: Int, isLoaded: Boolean): Boolean =
        isLoaded && alignedPage == canonicalPage
}
