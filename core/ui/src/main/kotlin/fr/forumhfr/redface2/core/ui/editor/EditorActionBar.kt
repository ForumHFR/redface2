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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
 * B5 chrome: width excludes safeDrawing; this bar owns its 16 dp gutters. The host reduces gestures
 * with reduceEditorBar, keeps selection (keep), applies BBCode and restores focus/viewport without
 * changing IME visibility; external surfaces restore focus on close. No field, picker launcher,
 * submission controller or preference lives here. Keep composed across events/arms for live node identity.
 * [firstStatus]/[secondStatus] are independent permanent slots (see [EditorAppBar]): null means absent,
 * never compacted into the other slot. ED-30 events mask the lowest-priority fixed slots until the
 * full ED-133 envelope fits; retained slots keep their resting positions and return on acknowledgement.
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
    val focus = rememberEditorBarFocus(mode)
    val back = {
        focus.navigateBack(mode) { actions.onGesture(EditorBarGesture.Back) }
    }
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
        val expanded = mode == EditorBarMode.Format
        val toggle = {
            focus.navigate(EditorBarFocusTarget.Format, EditorBarFocusTarget.Format) {
                actions.onGesture(EditorBarGesture.ToggleFormat)
            }
        }
        EditorBarIcon(R.drawable.ic_editor_format, stringResource(R.string.editor_format), toggle,
            focus.modifier(EditorBarFocusTarget.Format)
                .clip(MaterialTheme.shapes.small)
                .background(if (expanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics { selected = expanded }, enabled = enabled, expanded = expanded)
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
                            focus, format, external, insert, back)
                    }
                })
            EditorBarProgress(uploading, loadingForm, uploadProgress, Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun editorBarStatusWidths(statuses: List<EditorAppBarStatus?>): List<Dp?> {
    return statuses.map { status -> status?.let {
        maxOf(EditorChromeMetrics.minimumTargetSize, measureEditorStatusBadge(it.badgeCount).width)
    } }
}

private fun editorBarSubmitState(
    state: EditorBarState, submit: ArmedSubmitState, uploading: Boolean, loadingForm: Boolean,
): ArmedSubmitState = submit.copy(
    armed = submit.armed || state.arm != null,
    armToken = state.arm ?: submit.armToken,
    enabled = submit.enabled && !uploading && !loadingForm && !submit.showProgress,
)

private fun consumeEditorBarInformation(event: EditorEvent?, onEvent: (EventKey, EditorEventAction) -> Unit): Boolean {
    val information = event?.content is EditorEventContent.Information ||
        (event?.content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Information
    if (information && event != null) onEvent(event.key, EditorEventAction.Dismiss)
    return information
}

@Composable
@Suppress("LongParameterList") // All measured inputs are explicit; no asynchronous width feedback.
private fun EditorBarEnvelope(
    height: Dp, compact: Boolean, hasHeader: Boolean, statusWidths: List<Dp?>,
    mode: EditorBarMode, event: EditorEvent?,
    submit: @Composable () -> Unit,
    content: @Composable (EditorChromeAllocation) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth().height(height)) { constraints ->
        val button = subcompose("submit", submit).single()
            .measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = height.roundToPx()))
        val minimum = when {
            event != null -> EditorChromeMetrics.eventContentWidth
            mode == EditorBarMode.Format -> EditorChromeMetrics.formatMinimumWidth
            else -> EditorChromeMetrics.minimumContentWidth
        }
        val allocation = allocateEditorChrome(EditorChromeLayoutRequest(
            constraints.maxWidth.toDp(), button.width.toDp(), compact, hasHeader,
            firstStatusWidth = statusWidths[0], secondStatusWidth = statusWidths[1],
            minimumContentWidth = minimum,
        ))
        // ED-133 extends only an event adjacent to the gutter. ED-25 fixed slots never shift.
        val extension = if (event != null && allocation.fixedSlots.isEmpty())
            EditorChromeMetrics.eventGutterExtension else 0.dp
        val scrollingWidth = allocation.scrollingWidth + extension
        val leading = (EditorChromeMetrics.horizontalGutter - extension).roundToPx()
        val trailing = EditorChromeMetrics.horizontalGutter.roundToPx()
        val width = (constraints.maxWidth - leading - trailing - button.width).coerceAtLeast(0)
        val row = subcompose("content") {
            content(allocation.copy(scrollingWidth = scrollingWidth,
                hasMinimumContentWidth = scrollingWidth >= minimum))
        }.single()
            .measure(Constraints.fixed(width, height.roundToPx()))
        layout(constraints.maxWidth, height.roundToPx()) {
            row.placeRelative(leading, 0)
            button.placeRelative(constraints.maxWidth - trailing - button.width,
                (height.roundToPx() - button.height) / 2)
        }
    }
}

@Composable
@Suppress("LongParameterList") // Four independent slots share the exact same fixed/overflow dispatch.
private fun EditorBarFixedSlot(
    fixed: EditorFixedSlot, statuses: List<EditorAppBarStatus?>,
    headerLabel: String, onHeader: (() -> Unit)?,
    onPreview: () -> Unit, previewVisible: Boolean, enabled: Boolean, visible: Boolean,
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
    event: EditorEvent?, armed: Boolean, mode: EditorBarMode,
    allocation: EditorChromeAllocation, height: Dp,
    onEvent: (EventKey, EditorEventAction) -> Unit, scroll: ScrollState,
    renderSlot: @Composable (EditorFixedSlot, Boolean) -> Unit, format: @Composable () -> Unit,
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
            val eventWidth = (allocation.scrollingWidth - EditorChromeMetrics.eventSubmitGap)
                .coerceAtLeast(EditorChromeMetrics.minimumTargetSize)
            EditorEventControl(event, eventWidth, onEvent,
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
    enabled: Boolean, controlsEnabled: Boolean,
    focus: EditorBarFocus,
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
                { focus.navigate(EditorBarFocusTarget.Image, EditorBarFocusTarget.Return) {
                    actions.onGesture(EditorBarGesture.OpenImage)
                } }, focus.modifier(EditorBarFocusTarget.Image), enabled = enabled)
            EditorBarIcon(R.drawable.ic_editor_options, stringResource(R.string.editor_options),
                { external(actions.onOptions) }, enabled = controlsEnabled)
        }
        EditorBarMode.Format -> EditorFormatRow(insert, {
            focus.navigate(EditorBarFocusTarget.Color, EditorBarFocusTarget.Return) {
                actions.onGesture(EditorBarGesture.OpenColor)
            }
        }, enabled, focus.modifier(EditorBarFocusTarget.Color))
        EditorBarMode.Color -> Row(focus.sublevelModifier()) {
            EditorColorRow({ insert(it); actions.onGesture(EditorBarGesture.ColorChosen) },
                back, enabled, focus.modifier(EditorBarFocusTarget.Return))
        }
        EditorBarMode.Image -> Row(focus.sublevelModifier()) {
            EditorImageRow(images, external, {
                insert(BbcodeAction.Image); actions.onGesture(EditorBarGesture.ImageAction)
            }, back, enabled, focus.modifier(EditorBarFocusTarget.Return))
        }
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
private fun EditorBarProgress(uploading: Boolean, loading: Boolean, progress: UploadProgress?, modifier: Modifier) {
    if (!uploading && !loading) return
    val current = if (uploading) progress else null
    val track = modifier.fillMaxWidth().height(2.dp).testTag("editor-bar-progress").clearAndSetSemantics {}
    if (current == null || current.total <= 0) {
        LinearProgressIndicator(modifier = track, strokeCap = StrokeCap.Butt, gapSize = 0.dp)
    } else LinearProgressIndicator(progress = { (current.completed.toFloat() / current.total).coerceIn(0f, 1f) },
        modifier = track, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
}
