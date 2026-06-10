package fr.forumhfr.redface2.feature.topic

/**
 * #307 — raw scroll anchor of the topic page's `LazyColumn`, captured when the screen leaves the
 * composition and replayed when the user returns to the same `(cat, post, page)` (swipe back/forward,
 * FAB, header pager…). The route-driven page model destroys the nav entry — and with it the
 * `rememberSaveable` `LazyListState` — on every page change, so `:app` keeps these anchors in a
 * session-scoped cache hoisted above `NavDisplay` (twin of the per-topic title cache, PR #338).
 *
 * **Header-aware by construction**: [index]/[offset] are the raw
 * `LazyListState.firstVisibleItemIndex` / `firstVisibleItemScrollOffset`, where item 0 is the topic
 * header card and posts start at item 1 (cf. the `+1` in the `ScrollToPost` handling). Raw values are
 * exact even when the header is the first visible item — a « first visible post + offset » anchor
 * would be wrong there, because the offset would apply to the wrong item. Restoring through
 * `scrollToItem(index, offset)` is valid because the page recomposes with the identical item list
 * (same header, same cached posts); if the content did change (refresh appended posts), the call
 * clamps to bounds instead of failing.
 *
 * @property index `firstVisibleItemIndex` at departure (0 = header card visible at the top).
 * @property offset `firstVisibleItemScrollOffset` in pixels, only meaningful for [index].
 */
data class TopicScrollAnchor(val index: Int, val offset: Int)
