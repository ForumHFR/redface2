package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import fr.forumhfr.redface2.core.ui.list.LazyListScrollbar

/**
 * #300/#351 — the post-list overlay shared by the topic page and the private-message thread page: a
 * scrollable [LazyColumn] with the auto-hiding [LazyListScrollbar] riding its right edge, OUTSIDE the
 * scrolled element so the scrollbar stays put while the list (and any swipe-follow translation on it)
 * moves.
 *
 * Deliberately thin (ADR-013 — share the components, not the screens):
 *  - [contentPadding] and [verticalArrangement] have **no defaults**: the two screens use different
 *    densities (the topic reads 8/16/8/88-style insets from its display-metrics preset, the MP a flat
 *    16.dp with a 12.dp gap), so forcing every call-site to pass them keeps c1 from silently changing
 *    either screen's spacing.
 *  - the page-swipe machinery stays in the feature (it differs by lifecycle: the topic is route-driven,
 *    the MP paginates in place — see `PageSwipe.kt`/ADR-013) and is injected through [listModifier],
 *    applied to the **`LazyColumn`** (so the list follows the finger) and never to the outer [Box] (so
 *    the scrollbar overlay stays fixed). `PullToRefreshBox` is NOT absorbed here — it stays the
 *    feature's wrapper, since its refresh state belongs to the screen's ViewModel.
 *  - [showScrollbar] gates the scrollbar overlay off entirely. The scrollbar ALSO self-hides on the
 *    « afficher l'ascenseur » preference (`LazyListScrollbar` reads `LocalShowScrollbar`), so this flag
 *    is only for a call-site that wants no scrollbar at all regardless of that preference; both
 *    consumers leave it `true`.
 */
@Composable
@Suppress("LongParameterList") // List host: list state + density (padding/arrangement) + 2 modifiers + flag.
fun PostListScaffold(
    listState: LazyListState,
    contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    showScrollbar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(listModifier),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        if (showScrollbar) {
            LazyListScrollbar(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
