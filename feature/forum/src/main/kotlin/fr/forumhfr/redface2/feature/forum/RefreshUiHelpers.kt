package fr.forumhfr.redface2.feature.forum

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Suppresses a transient `Loading` re-emitted by a repository's `refresh*()` broadcast
 * when there is already a prior `Content` to keep showing. Shared between the Forum
 * and Category view models — both screens render a `PullToRefreshBox` that expects
 * the body to stay visible while the refresh round-trip is in flight, instead of
 * blanking back to a cold spinner.
 *
 * Behaviour pinned by `ForumViewModelTest` and `CategoryViewModelTest` :
 *
 * - first emission of `Loading` (cold start, no prior Content) **passes through** so
 *   the screen renders its initial spinner ;
 * - any later `Loading` while a prior `Content` exists is **suppressed** so the
 *   list stays visible under the refresh indicator ;
 * - `Error` always passes through (a failed refresh must surface the "Réessayer"
 *   button instead of silently keeping stale data).
 *
 * `T` is intentionally constrained to the screen's UI state sealed type ; the caller
 * supplies the two predicates because this helper does not depend on any specific
 * `*UiState` shape.
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
                // Suppress: keep showing the previous Content under a refresh spinner.
            }
            else -> emit(value)
        }
    }
}
