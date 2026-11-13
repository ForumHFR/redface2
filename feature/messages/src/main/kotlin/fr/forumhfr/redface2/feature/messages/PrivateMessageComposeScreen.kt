package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.LocalEditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientInput
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput
import fr.forumhfr.redface2.core.ui.editor.EditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.EditorUnsavedCloseDialog
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.LocalEditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.rememberEditorWindowMetrics

/** New MP on the shared chrome; private content and transient surface memory belong to the ViewModel. */
@Suppress("LongParameterList") // Navigation callbacks, including the editor-settings exit.
@Composable
fun PrivateMessageComposeScreen(
    onSubmitSucceeded: () -> Unit,
    // The host pops only after CloseCommitted, once the ViewModel has flushed the draft (#803).
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialRecipient: String? = null,
    viewModel: PrivateMessageComposeViewModel = hiltViewModel<
        PrivateMessageComposeViewModel, PrivateMessageComposeViewModel.Factory
    >(creationCallback = { factory -> factory.create(initialRecipient) }),
    onOpenEditorSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onStarted()
                Lifecycle.Event.ON_STOP -> viewModel.onStopped()
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
        withFrameNanos { }
        withFrameNanos { }
        if (measuredViewport == (bodyScroll.maxValue to bodyScroll.viewportSize)) viewModel.restoreBodyScroll()
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnSuccess by rememberUpdatedState(onSubmitSucceeded)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PrivateMessageComposeEffect.SubmitSucceeded -> currentOnSuccess()
                PrivateMessageComposeEffect.CloseCommitted -> currentOnBack()
            }
        }
    }
    // Above every branch of the content: loading, form failure and an open header panel included.
    if (state.showUnsavedCloseDialog) {
        EditorUnsavedCloseDialog(
            onStay = { viewModel.onUnsavedCloseDecided(leave = false) },
            onLeave = { viewModel.onUnsavedCloseDecided(leave = true) },
        )
    }
    CompositionLocalProvider(LocalEditorPreviewState provides viewModel.previewLayout) {
        PrivateMessageComposeContent(
            state, viewModel.bodyFieldState, viewModel.bodyInputTransformation,
            viewModel.recipientInput, viewModel.subjectInput, remember(viewModel) { viewModel.chromeActions() },
            viewModel.smileyPicker, modifier, viewModel.bodyScrollState, viewModel.editorEvents, viewModel.chromeMemory,
            bodyCaretRevealResetKey = remember(viewModel) { { viewModel.bodyCaretRevealResetKey } },
            onOpenEditorSettings = {
                // Capture the live draft even if Nav3 removes this composition before delivering ON_STOP.
                viewModel.onStopped()
                onOpenEditorSettings()
            },
        )
    }
}

@Composable
@Suppress("LongParameterList") // Live inputs, VM-owned memory and business ports match the other editor hosts.
internal fun PrivateMessageComposeContent(
    state: PrivateMessageComposeUiState,
    bodyFieldState: TextFieldState,
    bodyInputTransformation: InputTransformation,
    recipients: EditorRecipientInput,
    subject: EditorSubjectInput,
    actions: PrivateMessageComposeChromeActions,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
    bodyScrollState: ScrollState = rememberScrollState(),
    chromeEvents: PrivateMessageComposeEvents = remember { PrivateMessageComposeEvents() },
    chromeMemory: PrivateMessageComposeChromeMemory = remember {
        PrivateMessageComposeChromeMemory(SavedStateHandle())
    },
    windowMetrics: EditorWindowMetrics? = null,
    bodyCaretRevealResetKey: () -> Int = { 0 },
    onOpenEditorSettings: () -> Unit = {},
) {
    val source = rememberUpdatedState(state)
    // Register before loading/errors, outside transient surfaces: rotation never relaunches the picker.
    val adapter = remember { arrayOfNulls<PrivateMessageComposeChromeAdapter>(1) }
    val launchPicker = rememberEditorImagePicker(state.imagePickerMode,
        onCancelled = { adapter[0]?.closeSurface() }, onEvent = { adapter[0]?.picker(it) })
    val launch = rememberUpdatedState(launchPicker)
    val host = remember(bodyFieldState, chromeEvents, chromeMemory, smileyPicker, actions) {
        PrivateMessageComposeChromeAdapter({ source.value }, bodyFieldState, chromeEvents, actions,
            { launch.value() }, { smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION) }, chromeMemory,
            openSmileySearch = { smileyPicker.open(SmileyPickerOpenIntent.SEARCH) })
    }
    adapter[0] = host
    val activity = LocalActivity.current
    val window = windowMetrics ?: activity?.let { rememberEditorWindowMetrics(it).value }
    when {
        state.isInitializing || window == null -> ComposeFormStatus(host, modifier)
        state.formError || state.isLoadingForm && !state.formAvailable -> {
            // Only a failure drops the layers (gate C4 r1, minor 9); loading keeps them and the entry latch.
            if (state.formError) LaunchedEffect(host) { host.closeLayersForFormFailure { smileyPicker.dismiss() } }
            ComposeFormStatus(host, modifier)
        }
        else -> CompositionLocalProvider(LocalEditorTooltipHost provides remember { EditorTooltipHost() }) {
            ComposeChrome(host, bodyFieldState, bodyInputTransformation, bodyScrollState, recipients, subject,
                smileyPicker, window, modifier, bodyCaretRevealResetKey, onOpenEditorSettings)
        }
    }
}

@Composable
@Suppress("LongParameterList") // Scaffold assembly keeps the real body mounted beneath every panel.
private fun ComposeChrome(
    host: PrivateMessageComposeChromeAdapter,
    body: TextFieldState,
    transformation: InputTransformation,
    scroll: ScrollState,
    recipients: EditorRecipientInput,
    subject: EditorSubjectInput,
    smileys: SmileyPickerController,
    window: EditorWindowMetrics,
    modifier: Modifier,
    bodyCaretRevealResetKey: () -> Int,
    onOpenEditorSettings: () -> Unit,
) {
    val tooltips = requireNotNull(LocalEditorTooltipHost.current)
    val back = {
        host.back(EditorBackState(tooltipOpen = tooltips.isVisible)) {
            if (it == EditorBackAction.CloseTooltip) tooltips.dismiss()
        }
    }
    val focus = remember { FocusRequester() }
    // An empty entry belongs to the automatic header, without a body IME for the scaffold to restore.
    val autoFocus = host.memory.autoFocusBody(host.state.source.recipientState)
    // Freeze the policy only once this composition is applied, never from an abandoned one (gate C4 r1, minor 2).
    SideEffect { host.memory.retainAutoFocus(autoFocus) }
    EditorScaffold(body, window, host.title(), host.context(), host.chrome(), host.barActions,
        host.submitState, host.submitActions, host.imageActions, host::closeRequested, modifier = modifier,
        onOpenHeader = host::openHeader, headerLabel = stringResource(R.string.messages_compose_header_open),
        bodyFocusRequester = focus, bodyScrollState = scroll, inputTransformation = transformation,
        autoFocus = autoFocus, surfaceFocus = host.focusMemory,
        bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
        bodyCaretRevealResetKey = bodyCaretRevealResetKey,
        onSystemBack = back,
        fieldArea = { field -> PrivateMessageComposeChromePreview(host, body, focus, field) },
        overlay = { restore, content ->
            PrivateMessageComposeChromeSurfaces(host, body, recipients, subject, smileys, restore, content,
                onOpenEditorSettings)
            // Last handler: use the common ED-85 resolver after bar, details, sheets and panels.
            BackHandler(onBack = back)
        })
}

/** Preserve the full-screen form retry/loading states and the awaited close path. */
@Composable
private fun ComposeFormStatus(host: PrivateMessageComposeChromeAdapter, modifier: Modifier) {
    BackHandler { host.closeRequested() }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MessageEditorHeader(host.title(), host::closeRequested)
            if (host.state.source.formError) MessageFormErrorState(
                onRetry = host::retryFormLoad, message = stringResource(R.string.messages_compose_form_error))
            else MessageFormLoadingState()
        }
    }
}
