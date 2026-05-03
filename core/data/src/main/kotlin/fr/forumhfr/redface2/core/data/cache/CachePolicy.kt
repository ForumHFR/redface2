package fr.forumhfr.redface2.core.data.cache

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Single source of truth for cache freshness in the data layer.
 *
 * Why a flat object and not a class hierarchy: the rules are short, the matrix is
 * the thing reviewers want to see at a glance, and the prompt for #26 explicitly
 * asks not to multiply abstraction layers. A function like [isFresh] taking a
 * [Duration] keeps the policy itself injectable in tests via [Clock] without
 * dragging a `CacheRules` class around.
 *
 * TTLs were picked from the contract surface, not aesthetics:
 *
 * - `topicPage` (60s) — cache-affichable for snappy back-nav within a session,
 *   but short enough that returning to a topic 2 minutes later refetches and
 *   surfaces new replies. Pages mostly mutate by appended posts — too long a
 *   TTL means stale "page X / N total" indicators.
 * - `topicList` (30s) — listings churn quickly on a busy category. Same reason.
 * - `flags` (30s) — drapeaux are private, sensitive, and subject to user action
 *   (mark-read implicit). Manual refresh remains the primary trigger anyway.
 * - `categories` (24h) — the public list is 19 entries and only shifts on HFR
 *   moderation events. We keep memory-only, but a TTL still bounds an old
 *   `cachedCategories` against multi-day uptime sessions.
 * - `subcategories` (6h) — slightly more dynamic than categories but still rare
 *   enough that 6h is a generous upper bound.
 *
 * Manual refresh paths bypass these TTLs by design — they call the network
 * fetch directly without consulting [isFresh].
 */
internal object CachePolicy {

    val topicPage: Duration = Duration.ofSeconds(60)
    val topicList: Duration = Duration.ofSeconds(30)
    val flags: Duration = Duration.ofSeconds(30)
    val categories: Duration = Duration.ofHours(24)
    val subcategories: Duration = Duration.ofHours(6)

    /**
     * Returns true iff [fetchedAt] is within [ttl] of [now]. Negative deltas
     * (clock skew, fixture clocks running before fetch) count as fresh: we never
     * want to refetch just because the system clock moved backwards.
     */
    fun isFresh(fetchedAt: Instant, ttl: Duration, now: Instant): Boolean {
        val age = Duration.between(fetchedAt, now)
        if (age.isNegative) return true
        return age <= ttl
    }

    fun isFresh(fetchedAt: Instant, ttl: Duration, clock: Clock): Boolean =
        isFresh(fetchedAt, ttl, clock.instant())
}
