package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

internal data class FlagScrollEffectParams(
    val selectedTab: FlagTab,
    val renderedTab: FlagTab,
    val tabUnreadFilter: Pair<FlagTab, Boolean>,
    val recallListToTop: Boolean,
)

/**
 * #603 audit fix — drives the per-tab scroll effects (filter-flip reset #385 + landing recall #546)
 * for the RENDERED tab's own list. #742: during a tab switch the selected tab can advance before
 * its list payload arrives, so effects wait until [FlagScrollEffectParams.selectedTab] and
 * [FlagScrollEffectParams.renderedTab] match before touching a [LazyListState]. Keying these effects
 * on the tab's own list (forTab) rather than forType(flagType) stops DT/Super (both flagType==null →
 * cyan) from recalling the hidden Cyan list and eroding the per-tab scroll preservation (#695).
 * Extracted from FlagsRoute so its null-guard / branches stay off the cyclomatic budget (detekt
 * limit 15).
 */
@Composable
internal fun FlagScrollEffects(
    params: FlagScrollEffectParams,
    listState: LazyListState?,
    onRecallConsumed: () -> Unit,
) {
    if (params.selectedTab != params.renderedTab) return
    listState?.let { state ->
        if (params.tabUnreadFilter.first == params.renderedTab) {
            FilterFlipScrollResetEffect(tabUnreadFilter = params.tabUnreadFilter, listState = state)
        }
        RecallListToTopEffect(recall = params.recallListToTop, listState = state, onConsumed = onRecallConsumed)
    }
    LaunchedEffect(params.recallListToTop, listState) {
        if (params.recallListToTop && listState == null) onRecallConsumed()
    }
}

/**
 * #385 — « +lus » left the first re-appearing topics hidden above the viewport: the list state
 * anchors on the first VISIBLE item's key, so rows inserted above it (read topics re-shown)
 * require a manual scroll up to be discovered. Reset the hoisted [listState] to the top when the
 * « non-lus uniquement » filter flips ON THE SAME TAB — the user just asked for a different topic
 * set, show it from the start. Tab switches keep the current behaviour (no reset).
 *
 * [tabUnreadFilter] is the ViewModel's ATOMIC (tab, unreadOnly) pair — each filter value is
 * pinned to the tab that produced it (`flatMapLatest`), so a tab switch can never be observed as
 * « new tab + stale filter » then « new tab + real filter », which this effect would misread as a
 * same-tab flip and reset the scroll on every switch (Codex review on PR #421).
 */
@Composable
private fun FilterFlipScrollResetEffect(
    tabUnreadFilter: Pair<FlagTab, Boolean>,
    listState: LazyListState,
) {
    var lastFilterByTab by remember { mutableStateOf<Pair<FlagTab, Boolean>?>(null) }
    LaunchedEffect(tabUnreadFilter) {
        val previous = lastFilterByTab
        lastFilterByTab = tabUnreadFilter
        if (previous != null &&
            previous.first == tabUnreadFilter.first &&
            previous.second != tabUnreadFilter.second
        ) {
            // #603 — pin the top across a few frames (NOT a single scrollToItem(0)): the « +lus » flip
            // re-inserts read topics ABOVE the key-anchored visible item (#385), and the re-filtered list
            // lands a frame after this effect runs (StateFlow → combine). A single, one-frame-late
            // scrollToItem(0) let those re-inserted rows flash UNDER the translucent overlaid top bar
            // before snapping home (« ça sursaute, une partie de la liste passe derrière la top bar »,
            // XaTriX). Re-asserting requestScrollToItem(0) per remeasure pins index 0 whichever frame the
            // new list arrives on — same robust path as the #546 tab-switch recall (Codex-confirmed).
            listState.pinFirstItemAcrossFrames()
        }
    }
}

// #603 — pin the first item to the top, re-asserting across [RECALL_TO_TOP_FRAMES] remeasures. Uses
// requestScrollToItem (not scrollToItem): it instructs the NEXT remeasure to ignore the key-based
// position restoration and place index 0 first, so it wins even when the list data lands a frame later
// (repository SharedFlow → combine/flatMapLatest). The top contentPadding is still honoured by layout —
// this is about timing + the key anchor, not the padding. Shared by the « +lus » filter flip
// (FilterFlipScrollResetEffect) and the #546 landing recall (RecallListToTopEffect).
private suspend fun LazyListState.pinFirstItemAcrossFrames() {
    repeat(RECALL_TO_TOP_FRAMES) {
        requestScrollToItem(0)
        withFrameNanos { }
    }
}

// #546 — number of consecutive frames over which the « recall to top » scroll is re-asserted after a
// landing auto-refresh. requestScrollToItem is per-remeasure and not durable, while the refreshed list
// can land a frame or two after the signal (repository SharedFlow → combine/flatMapLatest), so a small
// window covers the prepend whichever frame it arrives on. Three is generous for an in-memory emission
// and the loop always terminates, so the one-shot signal is always consumed (no rotation replay).
private const val RECALL_TO_TOP_FRAMES = 3

/**
 * #546 — recall the list to the top after a LANDING auto-refresh (app open / tab switch / resume): the
 * refresh prepends freshly-surfaced flags and a held scroll position would leave them off-screen
 * (« faut scroller vers le haut », tinc/Lt Ripley). Driven by a one-shot [recall] signal (consumed via
 * [onConsumed] once handled) rather than a replayable counter, so a rotation / route recreation does not
 * replay a stale scroll. Return-from-topic refreshes never raise it. Extracted off FlagsRoute (matching
 * the other *Effect helpers) to keep that screen under the detekt cyclomatic-complexity threshold.
 */
@Composable
private fun RecallListToTopEffect(recall: Boolean, listState: LazyListState, onConsumed: () -> Unit) {
    LaunchedEffect(recall) {
        if (recall) {
            // #546 — when the refresh prepends freshly-surfaced flags the old top row stays anchored and
            // the new rows sit above the viewport; pinFirstItemAcrossFrames re-asserts requestScrollToItem(0)
            // so the top wins whichever frame the new list lands on. Bounded so a no-change landing still
            // disarms the signal. Codex review #546.
            listState.pinFirstItemAcrossFrames()
            onConsumed()
        }
    }
}
