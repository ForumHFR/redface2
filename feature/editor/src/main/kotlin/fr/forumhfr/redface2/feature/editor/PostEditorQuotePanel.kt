package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.QuoteCard
import fr.forumhfr.redface2.core.ui.editor.QuoteCardControls
import fr.forumhfr.redface2.core.ui.R as UiR

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
    var announcement by remember { mutableStateOf("") }
    val clearedLabel = stringResource(R.string.editor_quotes_cleared)
    val clearActionLabel = stringResource(R.string.editor_quotes_clear_all_a11y)
    val emptyFocus = remember { FocusRequester() }
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(quotes.isEmpty()) {
        if (cleared && quotes.isEmpty()) { emptyFocus.requestFocus(); cleared = false }
    }
    EditorHeaderPanel(
        adapter.surface == PostEditorChromeSurface.Quotes, stringResource(R.string.editor_quote_panel_title),
        bodyState, adapter::closeSurface, { adapter.returnToBody(onReturnToBody) },
        headerActions = {
            if (quotes.size >= 2) TextButton(onClick = {
                announcement = clearedLabel
                cleared = true
                adapter.clearQuotes()
            }, enabled = enabled, modifier = Modifier.semantics { contentDescription = clearActionLabel }) {
                Text(stringResource(R.string.editor_quotes_clear_all))
            }
        }, body = body,
    ) {
        // Outlives the last card, including a clear-all operation.
        Box(Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        })
        if (quotes.isEmpty()) Text(stringResource(R.string.editor_quote_panel_empty),
            Modifier.focusRequester(emptyFocus).focusable())
        PostEditorQuoteCards(quotes, enabled, adapter::quoteIntent, { emptyFocus.requestFocus() })
    }
}

/** The panel and capped zone share exact-snapshot actions, order, announcements and removal focus. */
@Composable
internal fun PostEditorQuoteCards(
    quotes: List<QuoteSelection>,
    enabled: Boolean,
    onIntent: (PostEditorIntent) -> Unit,
    onEmpty: () -> Unit,
) {
    var announcement by remember { mutableStateOf("") }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    val focus = remember(quotes.size) { List(quotes.size) { FocusRequester() } }
    LaunchedEffect(quotes) {
        pendingIndex?.let { index ->
            if (quotes.isEmpty()) onEmpty() else focus[index.coerceAtMost(quotes.lastIndex)].requestFocus()
            pendingIndex = null
        }
    }
    val removed = stringResource(UiR.string.editor_quote_a11y_removed)
    val moved = stringResource(UiR.string.editor_quote_a11y_moved)
    val remainingCount = (quotes.size - 1).coerceAtLeast(0)
    val remaining = pluralStringResource(R.plurals.editor_quotes_remaining, remainingCount, remainingCount)
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quotes.forEachIndexed { index, quote ->
            val move: (Int) -> Unit = { delta ->
                announcement = moved.format(quote.author, index + delta + 1)
                onIntent(PostEditorIntent.QuoteMoved(quote.numreponse, delta, quote))
            }
            key(quote) {
                QuoteCard(quote, QuoteCardControls(index > 0, index < quotes.lastIndex, enabled,
                    { move(-1) }, { move(1) }, {
                        // Two portions may share an author: the count makes each removal announceable.
                        announcement = removed.format(quote.author) + ". " + remaining
                        pendingIndex = index
                        onIntent(PostEditorIntent.QuoteRemoved(quote.numreponse, quote))
                    }), focus[index])
            }
        }
    }
}
