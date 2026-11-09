@file:Suppress("MatchingDeclarationName") // Public host descriptors live beside the bar.

package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

data class EditorActionBarActions(
    val onGesture: (EditorBarGesture) -> Unit,
    val onBbcode: (BbcodeAction) -> Unit,
    val onBodyFocus: () -> Unit,
    val onSmileys: () -> Unit,
    val onOptions: () -> Unit,
    val onPreview: () -> Unit,
    val onEvent: (EventKey, EditorEventAction) -> Unit,
)

/** R6 supplies availability, dispatch and focus restoration; this slot stores no preference. */
data class EditorBarShortcut(
    @param:DrawableRes val iconRes: Int,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * B5 chrome only. Width excludes the host's safeDrawing insets; this bar owns its 16 dp gutters.
 * The host reduces gestures with reduceEditorBar, keeps the live selection (keep), applies BBCode,
 * then restores focus/viewport without changing IME visibility. External surfaces restore focus
 * when they close. No field, picker launcher, submission controller or preference lives here.
 * Keep composed across events/arms: the event control and its live node retain their identity.
 * [firstStatus] and [secondStatus] are independent permanent slots, as in [EditorAppBar].
 * Pass null for absence; never compact the second state into the first slot.
 */
@Composable
@Suppress("LongParameterList") // Stateless rendering inputs, matching the app bar's host boundary.
fun EditorActionBar(
    state: EditorBarState,
    actions: EditorActionBarActions,
    submitState: ArmedSubmitState,
    submitActions: ArmedSubmitActions,
    imageActions: EditorBarImageActions,
    modifier: Modifier = Modifier,
    event: EditorEvent? = null,
    compactHeight: Boolean = false,
    firstStatus: EditorAppBarStatus? = null,
    secondStatus: EditorAppBarStatus? = null,
    onOpenHeader: (() -> Unit)? = null,
    headerLabel: String = stringResource(R.string.editor_header_topic),
    previewVisible: Boolean = false,
    shortcut: EditorBarShortcut? = null,
    controlsEnabled: Boolean = true,
    uploading: Boolean = false,
    uploadProgress: UploadProgress? = null,
    loadingForm: Boolean = false,
) {
    val buttonState = editorBarSubmitState(state, submitState, uploading, loadingForm)
    val armed = buttonState.armed
    val mode = if (armed) EditorBarMode.Normal else state.visibleMode
    val height = EditorChromeMetrics(compactHeight).actionBarHeight
    val normalScroll = rememberScrollState()
    val formatScroll = rememberScrollState()
    val colorScroll = rememberScrollState()
    val imageScroll = rememberScrollState()
    val statuses = listOf(firstStatus, secondStatus)
    val statusWidths = editorBarStatusWidths(statuses)
    val external: (() -> Unit) -> Unit = { action ->
        if (!consumeEditorBarInformation(event, actions.onEvent)) {
            actions.onGesture(EditorBarGesture.OpenSurface)
            action()
        }
    }
    val insert: (BbcodeAction) -> Unit = { action -> actions.onBbcode(action); actions.onBodyFocus() }
    val back = { actions.onGesture(EditorBarGesture.Back) }
    val backAction = resolveEditorBack(EditorBackState(bar = state))
    BackHandler(!armed && (backAction == EditorBackAction.CloseSublevel ||
        backAction == EditorBackAction.CloseFormat), onBack = back)
    val enabled = controlsEnabled && !uploading
    val scroll = when (mode) {
        EditorBarMode.Normal -> normalScroll
        EditorBarMode.Format -> formatScroll
        EditorBarMode.Color -> colorScroll
        EditorBarMode.Image -> imageScroll
    }
    val slot: @Composable (EditorFixedSlot, Boolean) -> Unit = { fixed, visible ->
        EditorBarFixedSlot(fixed, statuses, headerLabel, onOpenHeader, actions.onPreview,
            previewVisible, controlsEnabled, visible, external)
    }
    val format: @Composable () -> Unit = {
        EditorBarFormatToggle(mode == EditorBarMode.Format, enabled) {
            actions.onGesture(EditorBarGesture.ToggleFormat)
        }
    }
    Surface(modifier.fillMaxWidth().height(height), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
            EditorBarEnvelope(height, compactHeight, onOpenHeader != null, statusWidths, mode, event,
                submit = {
                    EditorBarSubmit(buttonState, submitActions) {
                        consumeEditorBarInformation(event, actions.onEvent)
                    }
                },
                content = { allocation ->
                    EditorBarRow(event, armed, mode, allocation, height, actions.onEvent, scroll, slot, format) {
                        EditorBarTools(mode, actions, imageActions, shortcut, enabled, controlsEnabled,
                            format, external, insert, back)
                    }
                })
            if (uploading || loadingForm) EditorBarProgress(if (uploading) uploadProgress else null,
                Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun editorBarStatusWidths(statuses: List<EditorAppBarStatus?>): List<Dp?> {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return statuses.map { status -> status?.let {
        maxOf(48.dp, with(density) { measurer.measure(it.badgeCount?.toString().orEmpty(), style)
            .size.width.toDp() } + 8.dp)
    } }
}

private fun editorBarSubmitState(
    state: EditorBarState,
    submit: ArmedSubmitState,
    uploading: Boolean,
    loadingForm: Boolean,
): ArmedSubmitState = submit.copy(
    armed = submit.armed || state.arm != null,
    armToken = state.arm ?: submit.armToken,
    enabled = submit.enabled && !uploading && !loadingForm && !submit.showProgress,
)

private fun consumeEditorBarInformation(
    event: EditorEvent?,
    onEvent: (EventKey, EditorEventAction) -> Unit,
): Boolean {
    val information = event?.content is EditorEventContent.Information ||
        (event?.content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Information
    if (information && event != null) onEvent(event.key, EditorEventAction.Dismiss)
    return information
}

@Composable
private fun EditorBarFormatToggle(expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    EditorBarIcon(R.drawable.ic_editor_format, stringResource(R.string.editor_format), onToggle,
        Modifier.clip(MaterialTheme.shapes.small)
            .background(if (expanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .semantics { selected = expanded }, enabled = enabled, expanded = expanded)
}

@Composable
@Suppress("LongParameterList") // All measured inputs are explicit; no asynchronous width feedback.
private fun EditorBarEnvelope(
    height: Dp,
    compact: Boolean,
    hasHeader: Boolean,
    statusWidths: List<Dp?>,
    mode: EditorBarMode,
    event: EditorEvent?,
    submit: @Composable () -> Unit,
    content: @Composable (EditorChromeAllocation) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth().height(height)) { constraints ->
        val button = subcompose("submit", submit).single()
            .measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = height.roundToPx()))
        // ED-133: every event extends into the leading gutter, never toward Send.
        val extension = if (event != null) 4.dp else 0.dp
        val statusSlots = listOf(EditorFixedSlot.FirstStatus, EditorFixedSlot.SecondStatus)
            .filterIndexed { index, _ -> statusWidths[index] != null }
        val measured = allocateEditorChrome(EditorChromeLayoutRequest(
            constraints.maxWidth.toDp() + extension, button.width.toDp(), compact, hasHeader,
            statusWidths.filterNotNull(),
            minimumContentWidth = when {
                event != null -> 189.dp + extension // event envelope + 8 dp Send gap
                mode == EditorBarMode.Format -> 96.dp // trigger + one full scroll target
                else -> 48.dp
            },
        ))
        val allocation = measured.copy(
            fixedSlots = measured.fixedSlots.map { it.resolveStatusSlot(statusSlots) },
            overflowSlots = measured.overflowSlots.map { it.resolveStatusSlot(statusSlots) },
        )
        val leading = (16.dp - extension).roundToPx()
        val width = (constraints.maxWidth - leading - 16.dp.roundToPx() - button.width).coerceAtLeast(0)
        val row = subcompose("content") { content(allocation) }.single()
            .measure(Constraints.fixed(width, height.roundToPx()))
        layout(constraints.maxWidth, height.roundToPx()) {
            row.placeRelative(leading, 0)
            button.placeRelative(constraints.maxWidth - 16.dp.roundToPx() - button.width,
                (height.roundToPx() - button.height) / 2)
        }
    }
}

private fun EditorFixedSlot.resolveStatusSlot(statusSlots: List<EditorFixedSlot>): EditorFixedSlot = when (this) {
    EditorFixedSlot.FirstStatus -> statusSlots[0]
    EditorFixedSlot.SecondStatus -> statusSlots[1]
    else -> this
}

@Composable
@Suppress("LongParameterList") // Four independent slots share the exact same fixed/overflow dispatch.
private fun EditorBarFixedSlot(
    fixed: EditorFixedSlot,
    statuses: List<EditorAppBarStatus?>,
    headerLabel: String,
    onHeader: (() -> Unit)?,
    onPreview: () -> Unit,
    previewVisible: Boolean,
    enabled: Boolean,
    visible: Boolean,
    external: (() -> Unit) -> Unit,
) {
    when (fixed) {
        EditorFixedSlot.Header -> EditorBarIcon(R.drawable.ic_editor_header, headerLabel,
            { onHeader?.let { external(it) } }, enabled = enabled)
        EditorFixedSlot.Preview -> EditorBarIcon(R.drawable.ic_editor_preview,
            stringResource(if (previewVisible) R.string.editor_preview_hide else R.string.editor_preview_show),
            { external(onPreview) }, enabled = enabled)
        else -> {
            val status = statuses[if (fixed == EditorFixedSlot.FirstStatus) 0 else 1]
            EditorStatusIcon(status?.iconRes ?: R.drawable.ic_editor_restore, status?.label.orEmpty(),
                { status?.let { external(it.onClick) } }, Modifier.testTag("editor-bar-${fixed.name}"),
                badgeCount = status?.badgeCount,
                enabled = enabled && status?.enabled == true, visible = visible && status != null)
        }
    }
}

@Composable
@Suppress("LongParameterList") // Slots separate permanent event identity from replaceable tools.
private fun EditorBarRow(
    event: EditorEvent?,
    armed: Boolean,
    mode: EditorBarMode,
    allocation: EditorChromeAllocation,
    height: Dp,
    onEvent: (EventKey, EditorEventAction) -> Unit,
    scroll: ScrollState,
    renderSlot: @Composable (EditorFixedSlot, Boolean) -> Unit,
    format: @Composable () -> Unit,
    tools: @Composable () -> Unit,
) {
    // Move the actual PR 2 nodes, not copies: fixed, scrolling and silent anchors share identity.
    val first = remember { movableContentOf<@Composable () -> Unit> { it() } }
    val second = remember { movableContentOf<@Composable () -> Unit> { it() } }
    val slot: @Composable (EditorFixedSlot, Boolean) -> Unit = { fixed, visible ->
        when (fixed) {
            EditorFixedSlot.FirstStatus -> first { renderSlot(fixed, visible) }
            EditorFixedSlot.SecondStatus -> second { renderSlot(fixed, visible) }
            else -> renderSlot(fixed, visible)
        }
    }
    val showTools = event == null && !armed
    Row(verticalAlignment = Alignment.CenterVertically) {
        allocation.fixedSlots.forEach { slot(it, true) }
        Box(Modifier.weight(1f)) {
            // Permanent call site, including absent events and arm masking.
            EditorEventControl(event, (allocation.scrollingWidth - 8.dp).coerceAtLeast(48.dp), onEvent,
                Modifier.testTag("editor-bar-event").then(if (event == null) Modifier else Modifier.height(height)))
            if (showTools) Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == EditorBarMode.Format) format()
                EditorBarScroll(scroll, Modifier.weight(1f)) {
                    allocation.overflowSlots.forEach { slot(it, true) }
                    tools()
                }
            }
            val displayed = allocation.fixedSlots + if (showTools) allocation.overflowSlots else emptyList()
            listOf(EditorFixedSlot.FirstStatus, EditorFixedSlot.SecondStatus)
                .filterNot { it in displayed }.forEach { slot(it, false) }
        }
    }
}

@Composable
@Suppress("LongParameterList") // Dispatch-only mode switch; layout and selection live elsewhere.
private fun EditorBarTools(
    mode: EditorBarMode,
    actions: EditorActionBarActions,
    images: EditorBarImageActions,
    shortcut: EditorBarShortcut?,
    enabled: Boolean,
    controlsEnabled: Boolean,
    format: @Composable () -> Unit,
    external: (() -> Unit) -> Unit,
    insert: (BbcodeAction) -> Unit,
    back: () -> Unit,
) {
    when (mode) {
        EditorBarMode.Normal -> {
            format()
            EditorBarIcon(R.drawable.ic_editor_smileys, stringResource(R.string.editor_smileys),
                { external(actions.onSmileys) }, enabled = enabled)
            shortcut?.let { EditorBarIcon(it.iconRes, it.label, it.onClick, enabled = enabled && it.enabled) }
            EditorBarIcon(R.drawable.ic_editor_image, stringResource(R.string.editor_bar_image),
                { actions.onGesture(EditorBarGesture.OpenImage) }, enabled = enabled)
            EditorBarIcon(R.drawable.ic_editor_options, stringResource(R.string.editor_options),
                { external(actions.onOptions) }, enabled = controlsEnabled)
        }
        EditorBarMode.Format -> EditorFormatRow(insert, { actions.onGesture(EditorBarGesture.OpenColor) }, enabled)
        EditorBarMode.Color -> EditorColorRow({
            insert(it); actions.onGesture(EditorBarGesture.ColorChosen)
        }, back, enabled)
        EditorBarMode.Image -> EditorImageRow(images, external, {
            insert(BbcodeAction.Image); actions.onGesture(EditorBarGesture.ImageAction)
        }, back, enabled)
    }
}

@Composable
private fun EditorBarScroll(scroll: ScrollState, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.testTag("editor-bar-scroll").drawWithContent {
        drawContent()
        val edge = 12.dp.toPx().coerceAtMost(size.width)
        if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(background, Color.Transparent),
            0f, edge), size = Size(edge, size.height))
        if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, background),
            size.width - edge, size.width), topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
    }) {
        Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun EditorBarProgress(progress: UploadProgress?, modifier: Modifier) {
    val track = modifier.fillMaxWidth().height(2.dp).testTag("editor-bar-progress").clearAndSetSemantics {}
    if (progress == null || progress.total <= 0) {
        LinearProgressIndicator(modifier = track, strokeCap = StrokeCap.Butt, gapSize = 0.dp)
    } else LinearProgressIndicator(progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
        modifier = track, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
}
