package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.QuoteCard
import fr.forumhfr.redface2.core.ui.editor.QuoteCardControls
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** ED-50/53: keep mounted around the body; the scaffold callback restores the captured IME state. */
@Composable
internal fun PostEditorQuotePanel(
    adapter: PostEditorChromeAdapter,
    bodyState: TextFieldState,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val quotes = adapter.state.source.quotes
    val enabled = adapter.state.scaffold.controlsEnabled
    val isOpen = adapter.surface == PostEditorChromeSurface.Quotes
    // Closing drops both the displayed message and any queued actions, even on an immediate reopening.
    var announcement by remember(isOpen) { mutableStateOf("") }
    val announcements = remember(isOpen) { Channel<String>(Channel.UNLIMITED) }
    val onAnnounce: (String) -> Unit = { announcements.trySend(it) }
    LaunchedEffect(announcements) {
        if (isOpen) {
            var message = announcements.receive()
            while (true) {
                // Android must observe an empty interval even between identical messages.
                announcement = ""
                delay(QUOTE_ANNOUNCEMENT_EMPTY_MILLIS)
                announcement = message
                // TalkBack rereads asynchronously: do not let a queued action erase this message early.
                delay(QUOTE_ANNOUNCEMENT_HOLD_MILLIS)
                message = withTimeoutOrNull(QUOTE_ANNOUNCEMENT_IDLE_MILLIS - QUOTE_ANNOUNCEMENT_HOLD_MILLIS) {
                    announcements.receive()
                } ?: run {
                    announcement = ""
                    announcements.receive()
                }
            }
        }
    }
    val clearedLabel = stringResource(R.string.editor_quotes_cleared)
    val emptyFocus = remember { FocusRequester() }
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(quotes.isEmpty()) {
        if (cleared && quotes.isEmpty()) { emptyFocus.requestFocus(); cleared = false }
    }
    EditorHeaderPanel(
        isOpen, stringResource(R.string.editor_quote_panel_title),
        bodyState, adapter::closeSurface, { adapter.returnToBody(onReturnToBody) },
        headerActions = {
            // With a button, its size alone defines this slot. Without one, reserve zero width in
            // the Row and place the 1 dp node inward, over the title's trailing edge, without clipping.
            val headerModifier = if (quotes.size >= 2) Modifier else
                Modifier.width(0.dp).wrapContentWidth(Alignment.End, unbounded = true)
            Box(headerModifier) {
                if (quotes.size >= 2) TextButton(onClick = {
                    onAnnounce(clearedLabel)
                    cleared = true
                    adapter.clearQuotes()
                }, enabled = enabled) {
                    // The visible label is also the single accessible name (label-in-name).
                    Text(stringResource(R.string.editor_quotes_clear_all))
                }
                // Pinned outside the scroll viewport; after the button so Android cannot occlude it.
                // Outlives every card and the button, including the final removal and clear-all.
                Box(Modifier.size(1.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                })
            }
        }, body = body,
    ) {
        // Preserve the former node's height AND its following 8 dp Column gap (Roborazzi geometry).
        Spacer(Modifier.size(1.dp))
        if (quotes.isEmpty()) Text(stringResource(R.string.editor_quote_panel_empty),
            Modifier.focusRequester(emptyFocus).focusable())
        PostEditorQuoteCards(quotes, enabled, adapter::quoteIntent, { emptyFocus.requestFocus() }, onAnnounce)
    }
}

/** Exact-snapshot actions, insertion order, announcements and removal focus inside the panel. */
@Composable
internal fun PostEditorQuoteCards(
    quotes: List<QuoteSelection>,
    enabled: Boolean,
    onIntent: (PostEditorIntent) -> Unit,
    onEmpty: () -> Unit,
    onAnnounce: (String) -> Unit,
) {
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<QuoteSelection, List<QuoteSelection>>?>(null) }
    var moveSeq by remember { mutableIntStateOf(0) }
    // Identical retries must renew expiry even when the request compares equal.
    LaunchedEffect(moveSeq) {
        if (pendingMove != null) {
            delay(QUOTE_MOVE_REQUEST_MILLIS)
            pendingMove = null
        }
    }
    val focus = remember(quotes.size) { List(quotes.size) { FocusRequester() } }
    // Every card captures the same request before the first list change consumes it.
    val moveRequest = pendingMove
    LaunchedEffect(quotes) {
        // A removal or replacement also consumes the request, without stealing its focus.
        if (pendingMove === moveRequest) pendingMove = null
        pendingIndex?.let { index ->
            if (quotes.isEmpty()) onEmpty() else focus[index.coerceAtMost(quotes.lastIndex)].requestFocus()
            pendingIndex = null
        }
    }
    val removed = stringResource(UiR.string.editor_quote_a11y_removed)
    val moved = stringResource(UiR.string.editor_quote_a11y_moved)
    val remainingCount = (quotes.size - 1).coerceAtLeast(0)
    val remaining = pluralStringResource(R.plurals.editor_quotes_remaining, remainingCount, remainingCount)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quotes.forEachIndexed { index, quote ->
            key(quote) {
                val moveUpFocus = remember { FocusRequester() }
                val moveDownFocus = remember { FocusRequester() }
                // Wait for the reordered card's buttons to receive their new enabled state.
                LaunchedEffect(quotes) {
                    if (moveRequest != null && moveRequest.first == quote &&
                        isQuoteReorder(moveRequest.second, quotes, quote)) {
                        when (index) {
                            0 -> moveDownFocus.requestFocus()
                            quotes.lastIndex -> moveUpFocus.requestFocus()
                        }
                        // Coalesced clicks announce the actual final position, never a stale index.
                        onAnnounce(moved.format(quote.author, index + 1))
                    }
                }
                val move: (Int) -> Unit = { delta ->
                    pendingMove = quote to quotes
                    moveSeq++
                    onIntent(PostEditorIntent.QuoteMoved(quote.numreponse, delta, quote))
                }
                QuoteCard(quote, QuoteCardControls(index > 0, index < quotes.lastIndex, enabled,
                    { move(-1) }, { move(1) }, {
                        onAnnounce(removed.format(quote.author) + ". " + remaining)
                        pendingIndex = index
                        onIntent(PostEditorIntent.QuoteRemoved(quote.numreponse, quote))
                    }), focus[index], moveUpFocus, moveDownFocus)
            }
        }
    }
}

private fun isQuoteReorder(before: List<QuoteSelection>?, after: List<QuoteSelection>, quote: QuoteSelection): Boolean =
    before != null && before.size == after.size && before.toSet() == after.toSet() &&
        before.indexOf(quote) != after.indexOf(quote)

// Filtered/no-op intents and opposite clicks coalesced before recomposition must not outlive the gesture.
private const val QUOTE_MOVE_REQUEST_MILLIS = 1_000L
// Longer than Compose's 100 ms recurring accessibility-event interval; no spoken suffix or node replacement.
private const val QUOTE_ANNOUNCEMENT_EMPTY_MILLIS = 200L
private const val QUOTE_ANNOUNCEMENT_HOLD_MILLIS = 500L
// Includes the minimum hold above: the last message disappears 2 s after it was displayed.
private const val QUOTE_ANNOUNCEMENT_IDLE_MILLIS = 2_000L
