package fr.forumhfr.redface2.feature.topic

/**
 * #394 — a topic was opened with a `scrollTo` anchor (the « dernier lu » `numreponse` carried by a
 * drapeau / deep link / search hit), but that post is NOT in the fetched page: it was DELETED on HFR
 * between the moment the anchor was recorded and now. Without a fallback the screen silently lands at
 * the top with no « dernier lu » cue, which reads as a random bug to the user (cf. the issue's
 * « Dintr-un lemn » repro).
 *
 * This pure resolver picks the LEAST-SURPRISING surviving post to land on instead, mirroring HFR
 * web's own « where I stopped » behaviour:
 *
 *  - the FIRST post whose `numreponse` is strictly greater than the deleted [target] — i.e. the post
 *    chronologically *just after* the one the user last read, which is exactly what they want to read
 *    next. HFR `numreponse` is a global monotonically-increasing id and a page lists posts in
 *    chronological (ascending `numreponse`) order, so a single forward scan finds it ;
 *  - else (every post on the page predates the deleted anchor — the deleted post sat at or past the
 *    tail of what we fetched) the LAST post of the page, the closest surviving neighbour below.
 *
 * Returns `null` only for an empty page (nothing to land on — the screen keeps the top landing). The
 * scan does NOT assume the list is pre-sorted beyond HFR's page order: `firstOrNull { it > target }`
 * is correct for an ascending list and degrades to « first greater in document order » otherwise,
 * never throwing.
 *
 * @param visiblePosts the `numreponse`s of the loaded page, in HFR page (chronological) order.
 * @param target the deleted anchor's `numreponse` (the caller only invokes this once it has confirmed
 *   `target !in visiblePosts`).
 */
internal fun resolveDeletedAnchorFallback(visiblePosts: List<Int>, target: Int): Int? {
    if (visiblePosts.isEmpty()) return null
    return visiblePosts.firstOrNull { it > target } ?: visiblePosts.last()
}
