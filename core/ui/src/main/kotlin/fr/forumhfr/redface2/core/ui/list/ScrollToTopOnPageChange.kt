package fr.forumhfr.redface2.core.ui.list

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * #351 — land at the top when a NEW page replaces the kept-on-screen previous one (in-place
 * pagination: the composition survives the page change, unlike the topic's route-driven model where
 * a fresh screen starts at the top for free). Keyed on the RENDERED page: a same-page refresh keeps
 * the read position. Only fires when a previous page was rendered in THIS composition and differs
 * (Codex review on the first cut): on the first Content render the guard is still null, so a
 * rotation / recreation with content already loaded keeps the position `rememberLazyListState` just
 * restored instead of being yanked back to the top.
 *
 * Moved verbatim from `PrivateMessageThreadScreen` to `:core:ui` (#351): a generic
 * [LazyListState] behaviour the private-message thread will call in c3. NOT invoked by
 * [fr.forumhfr.redface2.core.ui.post.PostListScaffold] — the topic is route-driven and does not want
 * it, so it stays an opt-in the call-site applies, not part of the scaffold.
 */
@Composable
fun ScrollToTopOnPageChange(listState: LazyListState, renderedPage: Int) {
    val lastRenderedPage = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(renderedPage) {
        if (lastRenderedPage.value != null && lastRenderedPage.value != renderedPage) {
            listState.scrollToItem(0)
        }
        lastRenderedPage.value = renderedPage
    }
}
