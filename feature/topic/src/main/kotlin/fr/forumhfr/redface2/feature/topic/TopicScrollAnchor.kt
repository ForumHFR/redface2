package fr.forumhfr.redface2.feature.topic

/**
 * #307 — raw scroll anchor of the topic page's `LazyColumn`. Since #895 étape 4 the nav entry — and
 * its `LazyListState` — survives in-topic page changes: intra-topic revisits (swipe back/forward,
 * FAB, header pager…) are landed by the retained `TopicViewModel`'s own per-page anchor map (F3).
 * The `:app` session cache hoisted above `NavDisplay` (twin of the per-topic title cache, PR #338)
 * now serves CROSS-ENTRY restores only — reopening the same `(cat, post, page)` later in the session.
 * (Pre-#895 the route-driven page model destroyed the entry on every page change, which is why the
 * cache lives in `:app`.)
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
