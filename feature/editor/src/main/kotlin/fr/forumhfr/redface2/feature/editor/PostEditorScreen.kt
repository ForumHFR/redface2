package fr.forumhfr.redface2.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.ui.editor.LocalEditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.LocalEditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.rememberEditorWindowMetrics

/**
 * Post-level editor screen. Phase 2C (#145) adds a Submit button that posts the
 * reply via [PostEditorViewModel.submit]. Successful submissions raise a one-shot
 * [PostEditorEffect.SubmitSucceeded] which the navigation host translates into a
 * back navigation + topic refresh.
 */
@Composable
fun PostEditorScreen(
    request: PostEditorRequest,
    onSubmitSucceeded: (
        targetPage: Int?, scrollTo: Int?, quotedNumreponses: List<Int>, operation: PostSubmitOperation,
    ) -> Unit,
    // #604 lot 4a — pops this editor AFTER the ViewModel flushed the draft (CloseCommitted).
    // Navigation is only invoked after CloseCommitted; `:app` supplies the callback.
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: PostEditorViewModel = hiltViewModel<PostEditorViewModel, PostEditorViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.submit(PostEditorIntent.Started)
                Lifecycle.Event.ON_STOP -> viewModel.submit(PostEditorIntent.Stopped)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val bodyScroll = viewModel.bodyScrollState
    val measuredViewport = bodyScroll.maxValue to bodyScroll.viewportSize
    LaunchedEffect(viewModel, state.isInitializing, measuredViewport) {
        if (state.isInitializing || measuredViewport.first == Int.MAX_VALUE) return@LaunchedEffect
        // BTF2 publishes its range before placement/caret following finishes. Let that pass complete
        // and observe a full frame with the same range/viewport, including the initial autofocus.
        withFrameNanos { }
        withFrameNanos { }
        if (measuredViewport == (bodyScroll.maxValue to bodyScroll.viewportSize)) viewModel.restoreBodyScroll()
    }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnSubmitSucceeded by rememberUpdatedState(onSubmitSucceeded)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PostEditorEffect.SubmitSucceeded ->
                    currentOnSubmitSucceeded(
                        effect.targetPage, effect.scrollTo, effect.quotedNumreponses, effect.operation,
                    )
                PostEditorEffect.CloseCommitted -> currentOnClose?.invoke()
            }
        }
    }
    CompositionLocalProvider(LocalEditorPreviewState provides viewModel.previewLayout) {
        PostEditorContent(
            state = state,
            bodyFieldState = viewModel.bodyFieldState,
            bodyInputTransformation = viewModel.bodyInputTransformation,
            bodyScrollState = viewModel.bodyScrollState,
            onIntent = remember(viewModel) { { intent: PostEditorIntent -> viewModel.submit(intent) } },
            // #441 — the picker is driven by the shared controller (same wiring as the MP
            // composers, cf. PrivateMessageReplyScreen) ; only SmileySelected stays an intent.
            smileyPicker = viewModel.smileyPicker,
            modifier = modifier,
            chromeEvents = viewModel.chromeEvents,
            chromeMemory = viewModel.chromeMemory,
            bodyCaretRevealResetKey = remember(viewModel) { { viewModel.bodyCaretRevealResetKey } },
        )
    }
}

@Composable
@Suppress("LongParameterList") // Live body and its synchronous guard accompany the UI projection.
internal fun PostEditorContent(
    state: PostEditorState,
    bodyFieldState: TextFieldState,
    bodyInputTransformation: InputTransformation?,
    onIntent: (PostEditorIntent) -> Unit,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
    bodyScrollState: ScrollState = rememberScrollState(),
    chromeEvents: PostEditorChromeEvents = remember { PostEditorChromeEvents() },
    chromeMemory: EditorHostMemory<PostEditorChromeSurface> = remember { EditorHostMemory() },
    windowMetrics: EditorWindowMetrics? = null,
    bodyCaretRevealResetKey: () -> Int = { 0 },
) {
    val source = rememberUpdatedState(state)
    val dispatch = rememberUpdatedState(onIntent)
    // Register all launchers before initialization and outside every transient surface.
    val adapter = remember { arrayOfNulls<PostEditorChromeAdapter>(1) }
    val launchPicker = rememberEditorImagePicker(state.imagePickerMode,
        onCancelled = { adapter[0]?.pickerCancelled() }, onEvent = { adapter[0]?.picker(it) })
    val currentLaunch = rememberUpdatedState(launchPicker)
    val host = remember(bodyFieldState, chromeEvents, chromeMemory, smileyPicker) {
        PostEditorChromeAdapter({ source.value }, bodyFieldState, chromeEvents, { dispatch.value(it) },
            { currentLaunch.value() }, { smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION) }, chromeMemory,
            openSmileySearch = { smileyPicker.open(SmileyPickerOpenIntent.SEARCH) })
    }
    adapter[0] = host // Bound before launcher registration effects can deliver a pending result.
    val activity = LocalActivity.current
    val measuredWindow = activity?.let { rememberEditorWindowMetrics(it).value }
    val window = windowMetrics ?: measuredWindow
    if (state.isInitializing || window == null) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    CompositionLocalProvider(LocalEditorTooltipHost provides remember { EditorTooltipHost() }) {
        PostEditorChrome(host, bodyFieldState, bodyInputTransformation, bodyScrollState, smileyPicker, window,
            modifier, bodyCaretRevealResetKey)
    }
}

@Composable
@Suppress("LongParameterList") // Screen assembly retains the live field, scroll and host-owned surfaces.
private fun PostEditorChrome(
    host: PostEditorChromeAdapter,
    body: TextFieldState,
    transformation: InputTransformation?,
    scroll: ScrollState,
    smileys: SmileyPickerController,
    window: EditorWindowMetrics,
    modifier: Modifier,
    bodyCaretRevealResetKey: () -> Int,
) {
    val state = host.state.source
    val tooltips = requireNotNull(LocalEditorTooltipHost.current)
    val back = {
        host.back(EditorBackState(tooltipOpen = tooltips.isVisible)) {
            if (it == EditorBackAction.CloseTooltip) tooltips.dismiss()
        }
    }
    val focus = remember { FocusRequester() }
    EditorScaffold(body, window, stringResource(state.mode.titleResId),
        state.topicTitle.takeUnless { state.isLoadingForm }.orEmpty(),
        host.chrome(), host.barActions, host.submitState, host.submitActions,
        host.imageActions, host::closeRequested, bodyFocusRequester = focus, bodyScrollState = scroll,
        modifier = modifier, inputTransformation = transformation, autoFocus = true, surfaceFocus = host.focusMemory,
        bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
        bodyCaretRevealResetKey = bodyCaretRevealResetKey,
        onSystemBack = back,
        fieldArea = { draft ->
            PostEditorPreview(state.isPreviewVisible, state.preview, body, focus) {
                draft()
            }
        }, overlay = { restore, content ->
            PostEditorChromeSurfaces(host, body, smileys, restore, window.compactHeight, content)
            // Registered after the bar and panels: all activity layers use the same ED-85 resolver.
            // Native sheet/detail/dialog dispatchers still consume their own levels first.
            // Reducing a sublevel or Format here still returns keyboard focus to its bar trigger.
            BackHandler(onBack = back)
        })
}

private val PostEditorMode.titleResId: Int
    get() = when (this) {
        PostEditorMode.Reply -> R.string.editor_post_reply_title
        PostEditorMode.Edit -> R.string.editor_post_edit_title
    }
