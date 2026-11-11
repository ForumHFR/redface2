package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.EditorChromeMetrics
import fr.forumhfr.redface2.core.ui.editor.QuoteCard
import fr.forumhfr.redface2.core.ui.editor.QuoteCardControls

/** Measured from the real field: line height, plus label/headroom/padding outside its text viewport. */
internal data class PostEditorFieldMetrics(val lineHeight: Dp, val decorationHeight: Dp, val minimumLines: Int)
internal data class PostEditorQuoteAllocation(val zoneHeight: Dp, val fieldHeight: Dp, val panelOnly: Boolean)

/** [fieldArea] is metrics.draftHeight(window minus insets), not the full window height. */
internal fun allocatePostEditorQuotes(
    fieldArea: Dp,
    chrome: EditorChromeMetrics,
    field: PostEditorFieldMetrics,
    cardHeight: Dp,
    hasQuotes: Boolean,
): PostEditorQuoteAllocation {
    require(field.lineHeight > 0.dp && field.decorationHeight >= 0.dp && field.minimumLines > 0)
    require(cardHeight >= 0.dp && fieldArea >= 0.dp)
    val minimum = maxOf(chrome.draftHeightFloor, field.lineHeight * field.minimumLines + field.decorationHeight)
    val budget = (fieldArea - minimum).coerceIn(0.dp, 112.dp)
    val zone = if (hasQuotes && cardHeight > 0.dp && budget >= cardHeight) budget else 0.dp
    return PostEditorQuoteAllocation(zone, fieldArea - zone, hasQuotes && zone == 0.dp)
}

/**
 * Detached field-area slot, below the app bar and above the action bar. PR 4 supplies measured field
 * metrics (3 lines at scale 1, 2 at 1.3). The real first card is measured BEFORE composing the field.
 * Opening geometry is retained through reorder/removal/clear; only a window/typography change reallocates.
 * Feed panelOnly back to adapter.chrome: ED-140 hides ❝ while the capped zone is usable.
 */
@Composable
@Suppress("LongParameterList") // Measured inputs and independent action/body slots at the allocation boundary.
internal fun PostEditorQuoteLayout(
    quotes: List<QuoteSelection>,
    chrome: EditorChromeMetrics,
    field: PostEditorFieldMetrics,
    enabled: Boolean,
    onIntent: (PostEditorIntent) -> Unit,
    onEmpty: () -> Unit,
    body: @Composable (PostEditorQuoteAllocation) -> Unit,
) {
    val firstCard = remember { quotes.firstOrNull() }
    val scroll = rememberScrollState()
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight)
        val card = subcompose("measure-card") {
            firstCard?.let { QuoteCard(it, QuoteCardControls(false, false, false, {}, {}, {})) }
        }.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth))
        val allocation = allocatePostEditorQuotes(constraints.maxHeight.toDp(), chrome, field,
            card?.height?.toDp() ?: 0.dp, firstCard != null)
        val zoneHeight = allocation.zoneHeight.roundToPx()
        // The allocation exists before this subcomposition; no onSizeChanged feedback frame.
        val cards = subcompose("cards") {
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                PostEditorQuoteCards(quotes, enabled, onIntent, onEmpty)
            }
        }.single().measure(Constraints.fixed(constraints.maxWidth, zoneHeight))
        val draft = subcompose("body") { Box(Modifier.fillMaxSize()) { body(allocation) } }.single()
            .measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight - zoneHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (zoneHeight > 0) cards.placeRelative(0, 0)
            draft.placeRelative(0, zoneHeight)
        }
    }
}
