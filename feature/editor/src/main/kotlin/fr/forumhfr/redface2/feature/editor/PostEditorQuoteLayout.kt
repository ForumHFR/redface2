package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.EditorChromeMetrics
import fr.forumhfr.redface2.core.ui.editor.QuoteCard
import fr.forumhfr.redface2.core.ui.editor.QuoteCardControls
import fr.forumhfr.redface2.core.ui.editor.editorHorizontalInsets

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
 * Field-area bench, below the app bar and above the action bar. The integrated screen uses
 * PostEditorMeasuredQuotes to obtain these metrics from a real BTF2 probe before composing the field.
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

/** ED-140/J: measure the actual BTF2 decoration and first card before composing the screen's live field. */
@Composable
@Suppress("LongParameterList") // Measurement policy and the full-window chrome slot are independent.
internal fun PostEditorMeasuredQuotes(
    quotes: List<QuoteSelection>,
    chrome: EditorChromeMetrics,
    modifier: Modifier,
    availableHeight: (Dp) -> Dp,
    content: @Composable (PostEditorQuoteAllocation) -> Unit,
) {
    val firstCard = remember { quotes.firstOrNull() }
    val openingCount = remember { quotes.size }
    val probe = remember { TextFieldState("M\nM\nM\nM\nM") }
    val viewport = remember { intArrayOf(0) }
    val density = LocalDensity.current
    val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val horizontal = editorHorizontalInsets()
    val horizontalPx = horizontal.getLeft(density, LayoutDirection.Ltr) +
        horizontal.getRight(density, LayoutDirection.Ltr)
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val width = (constraints.maxWidth - horizontalPx).coerceAtLeast(0)
        // This separate read-only probe is measured but never placed, focused or exposed to accessibility.
        val field = subcompose("measure-field") {
            BbcodeTextField(probe, stringResource(fr.forumhfr.redface2.core.ui.R.string.editor_scaffold_message),
                Modifier.fillMaxSize().padding(horizontal = 16.dp).clearAndSetSemantics {}, readOnly = true,
                showLabel = chrome.showFieldLabel, showKeyboardOnFocus = false,
                onViewportMeasured = { viewport[0] = it },
                onTextLayout = { textLayout[0] = it() })
        }.single().measure(Constraints.fixed(width, 160.dp.roundToPx()))
        val text = requireNotNull(textLayout[0])
        val metrics = PostEditorFieldMetrics(
            (text.getLineBottom(0) - text.getLineTop(0)).toDp(),
            (field.height - viewport[0]).coerceAtLeast(0).toDp(),
            if (density.fontScale >= 1.3f) 2 else 3)
        val card = subcompose("measure-card") {
            firstCard?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PostEditorClearQuotes(openingCount, false, {})
                    QuoteCard(it, QuoteCardControls(false, false, false, {}, {}, {}))
                }
            }
        }.firstOrNull()?.measure(Constraints(maxWidth = width))
        val allocation = allocatePostEditorQuotes(availableHeight(constraints.maxHeight.toDp()),
            chrome, metrics, card?.height?.toDp() ?: 0.dp, firstCard != null)
        val screen = subcompose("screen") { content(allocation) }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) { screen.placeRelative(0, 0) }
    }
}
