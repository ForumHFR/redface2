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
 *    feature-owned geometries (both mode-switched: `TopicListLayout.kt`, and the MP's
 *    `ThreadListLayout.kt` since #1046/#1050), so forcing every call-site to pass them keeps c1
 *    from silently changing either screen's spacing.
 *  - the page-swipe machinery stays in the feature (both hosts paginate in place since #895 étape 4,
 *    but the couplings differ: the topic always slides before switching; the MP slides only for a
 *    session-cache-warm target and otherwise returns to readable rest before its keep-content load.
 *    Its gate also composes refresh, zoom mutation, scrollbar drag and landing alignment; see
 *    `PageSwipe.kt` / ADR-013 amendée) and is injected through [listModifier],
 *    applied to the **`LazyColumn`** (so the list follows the finger) and never to the outer [Box] (so
 *    the scrollbar overlay stays fixed). The pull-to-refresh modifier and indicator are NOT absorbed
 *    here — they stay in each feature, since their refresh state belongs to the screen's ViewModel.
 *  - [showScrollbar] gates the scrollbar overlay off entirely. The scrollbar ALSO self-hides on the
 *    « afficher l'ascenseur » preference (`LazyListScrollbar` reads `LocalShowScrollbar`), so this flag
 *    is only for a call-site that wants no scrollbar at all regardless of that preference; both
 *    consumers leave it `true`.
 *  - [onScrollbarDragStateChanged] exposes the fast-scroll producer to feature-owned position
 *    persistence. It stays true through the final programmatic seek's idle frame, so that seek is
 *    never mistaken for a user list-scroll settle.
 */
@Composable
@Suppress("LongParameterList") // Shared list host: state, geometry, modifiers and behavior hooks.
fun PostListScaffold(
    listState: LazyListState,
    contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    showScrollbar: Boolean = true,
    // #182/#1040 — either reader's magnifier suspends native list scrolling while zoomed (the
    // vertical axis is then driven programmatically via dispatchRawDelta).
    userScrollEnabled: Boolean = true,
    onScrollbarDragStateChanged: (Boolean) -> Unit = {},
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
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        if (showScrollbar) {
            LazyListScrollbar(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
                onDragStateChanged = onScrollbarDragStateChanged,
            )
        }
    }
}
