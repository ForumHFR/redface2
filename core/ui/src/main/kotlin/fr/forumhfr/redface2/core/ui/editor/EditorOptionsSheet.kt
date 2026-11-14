package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.sheet.settledSheetWindowInsets

/** The two tabs of the Options sheet (#1417): the HFR options, then the available formats. */
enum class EditorOptionsTab { Options, Formats }

/**
 * The tabs of one opening of the Options sheet, as [EditorHost.optionsFormats] projects them: the
 * selected tab, whether the format rows may insert, and the two guarded gestures.
 */
class EditorOptionsFormats(
    val tab: EditorOptionsTab,
    val enabled: Boolean,
    val onTab: (EditorOptionsTab) -> Unit,
    val onFormat: (BbcodeAction) -> Unit,
)

/**
 * Bottom sheet of an editor's « Options » action (post, topic form, MP reply, new MP). With [formats],
 * a segmented « Options | Formats » switch heads the sheet (#1417, decision of 2026-11-13): the first
 * tab shows the surface's [content], the second the available formats. Without it, the sheet only
 * hosts [content]. The sheet stays a container: each surface supplies its options.
 *
 * One resting anchor (`skipPartiallyExpanded`): the sheet opens at its content height, capped by the
 * window, and the system Back closes it in one gesture from either tab (ED-85). The body scrolls under
 * the switch (landscape, large fonts: ED-71) and keeps the height of the options tab on the formats
 * tab, so the sheet does not jump between tabs. With tabs, that height never drops below
 * [TabbedBodyMinHeight] (#1417 Q-MP, XaTriX 2026-11-13): short options (MP) would otherwise leave
 * three formats visible. Rows are full-bleed: [content] applies the gutter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorOptionsSheet(
    onDismiss: () -> Unit,
    formats: EditorOptionsFormats? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tab = formats?.tab ?: EditorOptionsTab.Options
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = Modifier.testTag(EDITOR_OPTIONS_SHEET_TAG),
        // Tagbug DEV #2805926: size by the keyboard's destination, not by its retraction frames.
        contentWindowInsets = { settledSheetWindowInsets() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (formats != null) EditorOptionsTabs(tab, formats.onTab)
            // `weight(fill = false)` caps the body to what the switch leaves in a compact window.
            // A tab owns its scroll position: changing tab starts at the top.
            key(tab) {
                EditorOptionsBody(formats, Modifier.weight(1f, fill = false), content)
            }
        }
    }
}

/**
 * Measure Options first in the same pass, including when recreated directly on Formats. Only the
 * active body is placed; the measuring Options body has no accessible descendants on Formats.
 * No post-layout state write or fallback height can move the sheet's anchor on the next frame.
 */
@Composable
private fun EditorOptionsBody(
    formats: EditorOptionsFormats?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val showingFormats = formats?.tab == EditorOptionsTab.Formats
    Layout(modifier = modifier.fillMaxWidth(), content = {
        val optionsSemantics = if (showingFormats) Modifier.clearAndSetSemantics {}
        else Modifier.testTag(EDITOR_OPTIONS_SHEET_SCROLL_TAG)
        EditorOptionsScrollBody(optionsSemantics, content)
        if (formats != null && showingFormats) {
            EditorOptionsScrollBody(Modifier.testTag(EDITOR_OPTIONS_SHEET_SCROLL_TAG)) {
                EditorFormatsPage(formats.onFormat, formats.enabled)
            }
        }
    }) { measurables, constraints ->
        // The floor is a minimum of the options pane itself: both tabs keep one height, blank below.
        val floor = if (formats == null) 0 else TabbedBodyMinHeight.roundToPx().coerceAtMost(constraints.maxHeight)
        val options = measurables.first().measure(constraints.copy(minHeight = floor))
        val active = if (showingFormats) {
            measurables[1].measure(constraints.copy(minHeight = options.height, maxHeight = options.height))
        } else options
        layout(options.width, options.height) { active.placeRelative(0, 0) }
    }
}

@Composable
private fun EditorOptionsScrollBody(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/** Hand-made segmented switch: 56 dp track, 48 dp segments, Role.Tab in a selectable group. */
@Composable
private fun EditorOptionsTabs(selected: EditorOptionsTab, onSelect: (EditorOptionsTab) -> Unit) {
    Row(
        Modifier
            .padding(start = EditorChromeMetrics.horizontalGutter, end = EditorChromeMetrics.horizontalGutter,
                bottom = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        EditorOptionsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val label = stringResource(
                if (tab == EditorOptionsTab.Options) R.string.editor_options else R.string.editor_formats_tab,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .testTag(EDITOR_OPTIONS_TAB_TAG_PREFIX + tab.name)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Floor of a tabbed body: the formats intro and about five 64 dp format rows at font scale 1. Taller
 * options (post, topic form) keep their own height; the window still caps it.
 */
internal val TabbedBodyMinHeight = 384.dp

internal const val EDITOR_OPTIONS_SHEET_TAG = "editor-options-sheet"
internal const val EDITOR_OPTIONS_SHEET_SCROLL_TAG = "editor-options-sheet-scroll"
internal const val EDITOR_OPTIONS_TAB_TAG_PREFIX = "editor-options-tab-"
