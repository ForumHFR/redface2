package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
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
    quote: QuotedPostPreview,
    controls: QuoteCardControls,
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
                    quote.excerpt.ifBlank { stringResource(R.string.editor_quote_no_text) },
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
            )
        }
    }
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

@Composable
private fun QuoteCardAction(
    enabled: Boolean,
    label: String,
    glyph: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium)
    }
}
