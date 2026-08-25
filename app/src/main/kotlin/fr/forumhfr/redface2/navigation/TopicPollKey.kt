package fr.forumhfr.redface2.navigation

/**
 * #465 — composite cache key for the per-topic poll-expansion cache. HFR exposes at most ONE poll
 * per topic, so a topic-level key is enough — no need for a per-page or per-poll component. Same
 * `(cat, post)` rationale as [TopicTitleKey] / `MultiQuoteBasket`: a topic id is unique only per HFR
 * category, so keying by `post` alone could leak a manual choice across two categories that happen
 * to share the same id.
 */
internal data class TopicPollKey(val cat: Int, val post: Int)

// Upper bound on the per-topic poll-expansion cache, mirroring TOPIC_TITLE_CACHE_MAX / the scroll
// anchor cap. A long reading session opens many topics with polls; the cap keeps the map from
// growing unbounded for the app's lifetime. Eviction is least-recently-WRITTEN (like the scroll
// anchors): dropping an old manual choice just lets that topic fall back to the global default,
// which is harmless.
internal const val TOPIC_POLL_EXPANSION_CACHE_MAX = 128

/**
 * #465 — records the user's MANUAL poll-expansion choice for [key] (`true` = revealed, `false` =
 * collapsed), evicting the oldest entries past [TOPIC_POLL_EXPANSION_CACHE_MAX]. Twin of
 * [withScrollAnchor]: an update to an existing key is remove-then-reinsert so the entry moves to the
 * tail (least-recently-written eviction), and an unchanged value short-circuits to the same instance
 * so a redundant toggle never reallocates the map nor recomposes `RedfaceApp`.
 *
 * Only topics the user has manually toggled appear here; absence of a key means « follow the global
 * `topicPollsExpanded` default ». The map is hoisted into `RedfaceApp` (above `NavDisplay`), exactly
 * like the title / scroll-anchor caches — so collapsing or expanding a poll survives leaving and
 * reopening the topic within the session (and survived the per-page `TopicRoute` entry swap back
 * when page changes replaced the route, pre-#895 étape 4). In-memory only (session-scoped), reset
 * naturally when the app process dies.
 */
internal fun Map<TopicPollKey, Boolean>.withPollExpansion(
    key: TopicPollKey,
    expanded: Boolean,
): Map<TopicPollKey, Boolean> {
    if (this[key] == expanded) return this
    val updated = (if (containsKey(key)) this - key else this) + (key to expanded)
    return if (updated.size > TOPIC_POLL_EXPANSION_CACHE_MAX) {
        updated.entries.drop(updated.size - TOPIC_POLL_EXPANSION_CACHE_MAX).associate { it.toPair() }
    } else {
        updated
    }
}
