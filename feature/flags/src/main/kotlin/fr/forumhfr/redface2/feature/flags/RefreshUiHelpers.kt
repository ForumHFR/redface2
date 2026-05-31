package fr.forumhfr.redface2.feature.flags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Suppresses a transient `Loading` re-emitted by a repository `refresh*()` broadcast when a
 * prior `Content` is already showing, so the `PullToRefreshBox` body stays visible under the
 * refresh indicator instead of blanking back to a cold spinner.
 *
 * Fixes the #225 double loader: a swipe-to-refresh toggled `isRefreshing` (→ pull indicator)
 * AND let the repo re-emit `FlagsResult.Loading` (→ a second, centered spinner that also
 * blanked the list). Keeping the previous content during the refresh round-trip removes the
 * second spinner and the list flicker.
 *
 * Behaviour (mirrors the `:feature:forum` helper, pinned by `FlagsViewModelTest`):
 * - the first `Loading` (cold start, no prior content) passes through → initial spinner;
 * - any later `Loading` while a prior content exists is suppressed → list stays anchored;
 * - everything else (content, failure, `null`) passes through (a failed refresh must surface
 *   its error/retry, never silently keep stale data).
 *
 * NOTE: deliberate copy of `:feature:forum`'s identical `keepContentDuringRefresh`. Kept local
 * to avoid editing `:feature:forum` during concurrent work; extract to a shared module (e.g.
 * `:core:ui`) when deduplicating.
 */
internal fun <T : Any> Flow<T>.keepContentDuringRefresh(
    isLoading: (T) -> Boolean,
    isContent: (T) -> Boolean,
): Flow<T> = flow {
    var lastContent: T? = null
    collect { value ->
        when {
            isContent(value) -> {
                lastContent = value
                emit(value)
            }
            isLoading(value) && lastContent != null -> {
                // Suppress: keep showing the previous content under the refresh indicator.
            }
            else -> emit(value)
        }
    }
}
