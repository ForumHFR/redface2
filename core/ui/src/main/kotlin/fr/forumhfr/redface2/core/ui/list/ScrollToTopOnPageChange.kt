package fr.forumhfr.redface2.core.ui.list

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * #351 — land at the top when a NEW page replaces the kept-on-screen previous one (in-place
 * pagination: the composition survives the page change — as it now does in the topic too, #895
 * étape 4, where the in-VM engine owns richer landings instead). Keyed on the RENDERED page: a
 * same-page refresh keeps
 * the read position. Only fires when a previous page was rendered in THIS composition and differs
 * (Codex review on the first cut): on the first Content render the guard is still null, so a
 * rotation / recreation with content already loaded keeps the position `rememberLazyListState` just
 * restored instead of being yanked back to the top.
 *
 * Moved verbatim from `PrivateMessageThreadScreen` to `:core:ui` (#351). Since #1074 the MP thread
 * uses one feature-owned effect for both top and cited-message landings, so those two suspending
 * scrolls cannot race. This generic helper remains an opt-in (and characterized independently),
 * never a behaviour of [fr.forumhfr.redface2.core.ui.post.PostListScaffold] : the topic does not
 * want it either, because its in-VM engine resolves each landing by priority (anchor, bottom step,
 * top — #895 étape 4).
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
