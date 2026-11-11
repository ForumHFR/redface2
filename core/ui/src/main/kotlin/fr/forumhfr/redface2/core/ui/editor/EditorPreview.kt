package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorPreviewWidthClass
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/**
 * ED-60/62: one stable source slot, with two split directions and a covering fallback. No keyboard
 * commands on open/close/reflow. Only a divider release/custom action may explicitly hide the IME.
 * [mediaDiskCachePolicy] is mandatory, including smileys: MP hosts must pass DISABLED.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
// Assembly of closed/split/fallback and gesture ports; keep one source call site. Bounds are tested separately.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun EditorPreview(
    visible: Boolean,
    content: PostContent,
    bodyState: TextFieldState,
    bodyFocusRequester: FocusRequester,
    mediaDiskCachePolicy: PostMediaDiskCachePolicy,
    title: String,
    previewModifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val state = LocalEditorPreviewState.current ?: remember(bodyState) { EditorPreviewState() }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val widthClass = EditorPreviewWidthClass.fromWidthDp(with(density) { window.width.toDp().value })
    val horizontal = widthClass != EditorPreviewWidthClass.Compact || LocalEditorPreviewCompactHeight.current
    val direction = if (horizontal) EditorPreviewDirection.Horizontal else EditorPreviewDirection.Vertical
    val key = EditorPreviewKey(direction, widthClass)
    val ime = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    val returnToSource = LocalEditorPreviewReturnFocus.current
    val focusMemory = LocalEditorPreviewFocusMemory.current ?: remember(bodyState) { EditorPreviewFocusMemory() }
    val focusManager = LocalFocusManager.current
    val previewScroll = rememberScrollState()
    var minimumPx by remember(bodyState) { mutableStateOf<Int?>(null) }
    var fieldHeightPx by remember(bodyState) { mutableStateOf<Int?>(null) }
    var sourceExtraPx by remember(bodyState) { mutableStateOf(0) }
    var sourceHeightPx by remember(bodyState) { mutableStateOf(0) }
    var sourceFocused by remember(bodyState) { mutableStateOf(false) }
    LaunchedEffect(visible, bodyState) {
        if (!visible && focusMemory.wasOpen && focusMemory.returnFocus) {
            if (!sourceFocused && returnToSource != null) returnToSource() else {
                requestEditorBodyFocus(bodyState, bodyFocusRequester)
            }
        }
        focusMemory.wasOpen = visible
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val extent = if (horizontal) maxWidth.value else maxHeight.value
        val minimum = minimumPx?.let { with(density) { it.toDp().value } }
        val tooSmall = maxWidth < PREVIEW_DIVIDER_TARGET_DP.dp || maxHeight < PREVIEW_DIVIDER_TARGET_DP.dp
        val bounds = if (tooSmall) null
            else editorPreviewBounds(extent, direction, ime, minimum,
                sourceExtra = if (horizontal) 0f else with(density) { sourceExtraPx.toDp().value })
        val split = visible && bounds != null
        // The scaffold consumes max(IME, navigation bars) once, below the action bar. Recover only
        // the IME's excess to test the SAME window with its keyboard closed, including floating IMEs.
        val imeExtra = with(density) {
            (WindowInsets.ime.getBottom(this) - WindowInsets.navigationBars.getBottom(this))
                .coerceAtLeast(0).toDp().value
        }
        val closedBounds = if (maxWidth < PREVIEW_DIVIDER_TARGET_DP.dp ||
            maxHeight.value + imeExtra < PREVIEW_DIVIDER_TARGET_DP) null else {
            editorPreviewBounds(if (horizontal) extent else extent + imeExtra, direction, false, minimum,
                sourceExtra = if (horizontal) 0f else with(density) { sourceExtraPx.toDp().value })
        }
        // ED-61/62: the keyboard has priority whenever it alone prevents the split, regardless of
        // focus. Keep the source available for recreation and panel returns, and retain ED-63 position.
        val hiddenByIme = visible && !split && ime && closedBounds != null
        val rendered = visible && !hiddenByIme
        val covering = rendered && !split
        SideEffect { focusMemory.hiddenByIme = hiddenByIme }
        val availability = when {
            !rendered || split -> EditorBodyFocusAvailability.Ready
            tooSmall || fieldHeightPx == 0 || minimumPx != null -> EditorBodyFocusAvailability.Blocked
            else -> EditorBodyFocusAvailability.Pending
        }
        LaunchedEffect(availability, sourceFocused, bodyState) {
            if (availability == EditorBodyFocusAvailability.Blocked && sourceFocused) {
                // ED-62/85: a covered field cannot keep receiving keys. BTF2 collapses a range on
                // genuine focus loss; preserve both oriented endpoints, without touching text/undo.
                // ED-61: end focus/input only; never command IME hide/show for preview transitions.
                focusMemory.returnFocus = true
                val selection = bodyState.selection
                focusManager.clearFocus(force = true)
                if (bodyState.selection != selection) bodyState.edit { this.selection = selection }
            }
        }
        // A window/IME/font change cancels a gesture instead of releasing it against obsolete bounds.
        var drag by remember(key, bounds, visible) { mutableStateOf<EditorPreviewDrag?>(null) }
        var raw by remember(key, bounds, visible) { mutableStateOf<Float?>(null) }
        val restored = bounds?.restore(state.sourceDp(key)) ?: 0f
        val elastic = !horizontal && ime
        val overdrag = if (bounds != null && elastic) drag?.overdrag(bounds) ?: 0f else 0f
        // ED-62: pane allocations always respect the floors, including during an overdrag.
        val source = bounds?.restore(raw ?: restored) ?: extent
        val elasticOffset = (overdrag * PREVIEW_ELASTIC_FACTOR)
            .coerceIn(-PREVIEW_DIVIDER_DP / 2f, PREVIEW_DIVIDER_DP / 2f)
        val persist: (Float) -> Unit = { value ->
            bounds?.let { state.rememberPosition(key, it.restore(value)) }
        }
        Layout(content = {
            Box(Modifier.fillMaxSize().onSizeChanged { sourceHeightPx = it.height }.onFocusChanged {
                sourceFocused = it.hasFocus
                if (it.hasFocus) focusMemory.returnFocus = true
                else if (!visible && inputMode.inputMode != InputMode.Keyboard) focusMemory.returnFocus = false
            }.focusProperties {
                onEnter = { if (covering) cancelFocusChange() }
            }.focusGroup().then(if (covering) Modifier.clearAndSetSemantics {
                hideFromAccessibility()
            } else Modifier)) {
                CompositionLocalProvider(
                    LocalEditorPreviewBodyFocusAvailability provides availability,
                    LocalEditorPreviewFieldHeight provides { fieldHeightPx = it },
                    LocalEditorPreviewFieldMinimum provides { minimum, fieldHeight ->
                        minimumPx = minimum; sourceExtraPx = (sourceHeightPx - fieldHeight).coerceAtLeast(0)
                    },
                ) { body() }
            }
            // This is always child #1: showing/reflowing the preview never moves the source slot.
            Box(Modifier.fillMaxSize().then(if (rendered) Modifier.testTag(
                if (split) "editor-preview-rendered" else "editor-preview-fallback") else Modifier)) {
                if (rendered) Surface(Modifier.fillMaxSize().then(previewModifier)
                    .pointerInput(Unit) { detectTapGestures {} }.semantics { paneTitle = title },
                    color = MaterialTheme.colorScheme.surface) {
                    Box {
                        BbcodePreview(content, Modifier.fillMaxSize().verticalScroll(previewScroll),
                            mediaDiskCachePolicy = mediaDiskCachePolicy)
                        if (overdrag != 0f) Surface(Modifier.align(Alignment.TopCenter),
                            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(R.string.editor_preview_release_keyboard),
                                Modifier.padding(8.dp).testTag("editor-preview-release"),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (split) PreviewDivider(horizontal, Triple(key, bounds, ime),
                elasticOffsetDp = elasticOffset,
                onStart = { drag = EditorPreviewDrag(restored); raw = restored },
                onDelta = { delta -> drag?.move(delta / density.density); raw = drag?.raw },
                onStop = {
                    val gesture = drag
                    if (gesture != null && bounds != null) {
                        persist(gesture.raw)
                        if (gesture.release(bounds, elastic)) keyboard?.hide()
                    }
                    drag = null; raw = null
                },
                onCancel = { drag = null; raw = null },
                onGrow = { persist(restored - PREVIEW_ACCESSIBLE_STEP_DP) },
                onShrink = { persist(restored + PREVIEW_ACCESSIBLE_STEP_DP) },
                onCloseKeyboard = { keyboard?.hide() })
        }) { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val divider = PREVIEW_DIVIDER_DP.dp.roundToPx()
            val target = PREVIEW_DIVIDER_TARGET_DP.dp.roundToPx()
            val sourcePx = if (split) (source.dp.roundToPx()).coerceIn(0,
                ((if (horizontal) width else height) - divider).coerceAtLeast(0))
                else if (horizontal) width else height
            val sourceBox = children[0].measure(Constraints.fixed(
                if (horizontal) sourcePx else width, if (horizontal) height else sourcePx))
            val previewBox = children[1].measure(Constraints.fixed(
                if (split && horizontal) width - sourcePx - divider else width,
                if (split && !horizontal) height - sourcePx - divider else height))
            val handle = children.getOrNull(2)?.measure(Constraints.fixed(
                if (horizontal) target else width, if (horizontal) height else target))
            layout(width, height) {
                sourceBox.placeRelative(0, 0)
                if (rendered) previewBox.placeRelative(
                    if (split && horizontal) sourcePx + divider else 0,
                    if (split && !horizontal) sourcePx + divider else 0)
                // 18 dp in the allocation, with 15 dp of touch slop overlapping each adjacent pane.
                handle?.placeRelative(if (horizontal) sourcePx - (target - divider) / 2 else 0,
                    if (!horizontal) sourcePx - (target - divider) / 2 else 0)
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // Independent gesture and accessible actions on a single divider target.
private fun PreviewDivider(
    horizontal: Boolean,
    gestureKey: Any,
    elasticOffsetDp: Float,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onGrow: () -> Unit,
    onShrink: () -> Unit,
    onCloseKeyboard: () -> Unit,
) {
    val name = stringResource(R.string.editor_preview_divider)
    val grow = stringResource(R.string.editor_preview_grow)
    val shrink = stringResource(R.string.editor_preview_shrink)
    val close = stringResource(R.string.editor_preview_close_keyboard)
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    Box(Modifier.fillMaxSize().testTag("editor-preview-divider-target")
        .pointerInput(horizontal, gestureKey) {
            detectDragGestures(onDragStart = { start() }, onDragEnd = { stop() }, onDragCancel = { cancel() }) {
                    change, amount ->
                change.consume()
                delta(if (horizontal) amount.x else amount.y)
            }
        }
        .semantics {
            contentDescription = name
            customActions = listOf(
                CustomAccessibilityAction(grow) { onGrow(); true },
                CustomAccessibilityAction(shrink) { onShrink(); true },
                CustomAccessibilityAction(close) { onCloseKeyboard(); true },
            )
        }.focusable(), contentAlignment = Alignment.Center) {
        Box((if (horizontal) Modifier.width(PREVIEW_DIVIDER_DP.dp).fillMaxHeight()
                else Modifier.height(PREVIEW_DIVIDER_DP.dp).fillMaxWidth())
            // Elastic feedback only: neither the panes nor the 48 dp gesture target move.
            .graphicsLayer { translationY = elasticOffsetDp * density }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("editor-preview-divider"),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (horizontal) 4.dp else 32.dp, if (horizontal) 32.dp else 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraSmall))
        }
    }
}

private const val PREVIEW_ELASTIC_FACTOR = 0.2f
private const val PREVIEW_ACCESSIBLE_STEP_DP = 32f
