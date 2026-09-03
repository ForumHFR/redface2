package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.R

/**
 * #604 lots 2-3 — one quote card: « ❝ author — excerpt » on a single line, with reorder and
 * remove affordances. Born in the quick-reply sheet (lot 2), promoted to `:core:ui` when the
 * full-screen editor adopted the same cards (lot 3, mockup P3) — one rendering for the one
 * mental model « citations = cartes, champ = texte ». Reordering is up/down buttons by design
 * (cadrage : a11y first, drag deferred) ; first/last are disabled instead of hidden so TalkBack
 * users hear a stable layout.
 */
@Composable
fun QuoteCard(
    quote: QuoteSelection,
    controls: QuoteCardControls,
    removeFocusRequester: FocusRequester? = null,
) {
    val moveUpLabel = stringResource(R.string.editor_quote_move_up, quote.author)
    val moveDownLabel = stringResource(R.string.editor_quote_move_down, quote.author)
    val removeLabel = stringResource(R.string.editor_quote_remove, quote.author)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.editor_quote_line,
                    quote.author,
                    quote.displayExcerpt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            QuoteCardAction(
                enabled = controls.enabled && controls.canMoveUp,
                label = moveUpLabel,
                glyph = "↑",
                onClick = controls.onMoveUp,
            )
            QuoteCardAction(
                enabled = controls.enabled && controls.canMoveDown,
                label = moveDownLabel,
                glyph = "↓",
                onClick = controls.onMoveDown,
            )
            QuoteCardAction(
                enabled = controls.enabled,
                label = removeLabel,
                glyph = "✕",
                onClick = controls.onRemove,
                focusRequester = removeFocusRequester,
            )
        }
    }
}

@Composable
private fun QuoteSelection.displayExcerpt(): String {
    val renderedExcerpt = excerpt.ifBlank { stringResource(R.string.editor_quote_no_text) }
    return if (truncate && !renderedExcerpt.endsWith(" [...]")) "$renderedExcerpt [...]" else renderedExcerpt
}

/** Reorder/remove affordances of one card, bundled for detekt's parameter budget. */
data class QuoteCardControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val enabled: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onRemove: () -> Unit,
)

/** Per-numreponse mutation callbacks of a [QuoteCardsColumn] (controls derived per index). */
data class QuoteCardsCallbacks(
    val onMoveUp: (numreponse: Int) -> Unit,
    val onMoveDown: (numreponse: Int) -> Unit,
    val onRemove: (numreponse: Int) -> Unit,
)

/**
 * #604 lot 4a — the whole cards block, shared by the quick-reply sheet and the full-screen
 * editor : one [QuoteCard] per entry PLUS the accessibility plumbing the per-card labels can't
 * provide (they describe the ACTION, not its result — cadrage Codex) :
 *
 * - a polite live region announcing each mutation (« Citation de X retirée », « … déplacée en
 *   position N ») through an invisible zero-sized node that OUTLIVES the cards, so the removal
 *   of the last card is still spoken ;
 * - focus restoration after a removal : the ✕ of the card that takes the removed slot (next,
 *   else previous) so TalkBack/keyboard users are not silently dropped ; with no card left the
 *   focus is released to the surface's natural order.
 *
 * Always compose it (it renders nothing visible when [quotes] is empty) — hiding it behind an
 * `if` would kill the pending announcement with the last card.
 */
@Composable
fun QuoteCardsColumn(
    quotes: List<QuoteSelection>,
    enabled: Boolean,
    callbacks: QuoteCardsCallbacks,
    modifier: Modifier = Modifier,
) {
    var announcement by remember { mutableStateOf("") }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    val removeFocusRequesters = remember(quotes.size) { List(quotes.size) { FocusRequester() } }
    // Invisible live region : TalkBack re-announces whenever the description CHANGES.
    Box(
        modifier = Modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    )
    LaunchedEffect(quotes) {
        val target = pendingFocusIndex ?: return@LaunchedEffect
        pendingFocusIndex = null
        removeFocusRequesters.getOrNull(target.coerceAtMost(quotes.lastIndex))
            ?.takeIf { quotes.isNotEmpty() }
            ?.requestFocus()
    }
    val movedTemplate = stringResource(R.string.editor_quote_a11y_moved)
    val removedTemplate = stringResource(R.string.editor_quote_a11y_removed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        quotes.forEachIndexed { index, quote ->
            QuoteCard(
                quote = quote,
                controls = QuoteCardControls(
                    canMoveUp = index > 0,
                    canMoveDown = index < quotes.lastIndex,
                    enabled = enabled,
                    onMoveUp = {
                        announcement = movedTemplate.format(quote.author, index)
                        callbacks.onMoveUp(quote.numreponse)
                    },
                    onMoveDown = {
                        announcement = movedTemplate.format(quote.author, index + 2)
                        callbacks.onMoveDown(quote.numreponse)
                    },
                    onRemove = {
                        announcement = removedTemplate.format(quote.author)
                        pendingFocusIndex = index
                        callbacks.onRemove(quote.numreponse)
                    },
                ),
                removeFocusRequester = removeFocusRequesters[index],
            )
        }
    }
}

@Composable
private fun QuoteCardAction(
    enabled: Boolean,
    label: String,
    glyph: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = base.semantics { contentDescription = label },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium)
    }
}
