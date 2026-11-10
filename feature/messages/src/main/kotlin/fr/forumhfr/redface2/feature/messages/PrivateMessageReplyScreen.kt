package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.LocalEditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.rememberEditorWindowMetrics

/**
 * Reply editor for a private-message conversation, hosted by the shared editor chrome.
 * Submission goes through
 * [PrivateMessageReplyViewModel]; a successful send raises [PrivateMessageReplyEffect.SubmitSucceeded]
 * which the navigation host turns into a back navigation + a forced conversation reload.
 */
@Composable
fun PrivateMessageReplyScreen(
    request: PrivateMessageReplyRequest,
    onSubmitSucceeded: (threadId: Int, page: Int) -> Unit,
    // #803 pattern — the actual pop. Invoked only on CloseCommitted (after the ViewModel flushed
    // the draft), never directly by the chrome: both the system back and the header arrow route
    // through PrivateMessageReplyViewModel.onCloseRequested first.
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrivateMessageReplyViewModel = hiltViewModel<
        PrivateMessageReplyViewModel, PrivateMessageReplyViewModel.Factory
    >(creationCallback = { factory -> factory.create(request) }),
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
                is PrivateMessageReplyEffect.SubmitSucceeded ->
                    currentOnSuccess(effect.threadId, effect.page)
                // #803 pattern — the pop happens only AFTER the ViewModel flushed the draft.
                PrivateMessageReplyEffect.CloseCommitted -> currentOnBack()
            }
        }
    }
    PrivateMessageReplyContent(
        state, request.openRecipientManager, viewModel.bodyFieldState, viewModel.bodyInputTransformation,
        remember(viewModel) { viewModel.chromeActions() }, viewModel.smileyPicker, modifier, viewModel.bodyScrollState,
        viewModel.editorEvents, viewModel.chromeMemory,
        bodyCaretRevealResetKey = remember(viewModel) { { viewModel.bodyCaretRevealResetKey } },
    )
}

@Composable
@Suppress("LongParameterList") // Live field, VM-owned chrome and business ports match the post host.
internal fun PrivateMessageReplyContent(
    state: PrivateMessageReplyUiState,
    autoOpenRecipientManager: Boolean,
    bodyFieldState: TextFieldState,
    bodyInputTransformation: InputTransformation,
    actions: PrivateMessageReplyChromeActions,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
    bodyScrollState: ScrollState = rememberScrollState(),
    chromeEvents: PrivateMessageReplyEvents = remember { PrivateMessageReplyEvents() },
    chromeMemory: PrivateMessageReplyChromeMemory = remember { PrivateMessageReplyChromeMemory() },
    windowMetrics: EditorWindowMetrics? = null,
    bodyCaretRevealResetKey: () -> Int = { 0 },
) {
    val source = rememberUpdatedState(state)
    // Register the launcher before loading/errors and outside every transient surface, like the post.
    val adapter = remember { arrayOfNulls<PrivateMessageReplyChromeAdapter>(1) }
    val launchPicker = rememberEditorImagePicker(state.imagePickerMode,
        onCancelled = { adapter[0]?.pickerCancelled() }, onEvent = { adapter[0]?.picker(it) })
    val launch = rememberUpdatedState(launchPicker)
    val host = remember(bodyFieldState, chromeEvents, chromeMemory, smileyPicker, actions) {
        PrivateMessageReplyChromeAdapter({ source.value }, bodyFieldState, chromeEvents,
            actions, { launch.value() }, smileyPicker::open, chromeMemory)
    }
    adapter[0] = host
    // Preserve the old one-shot entry latch even while a form error replaces the entire editor.
    // An open manager lives in VM memory only; process recreation must never reopen that sheet.
    var recipientEntryHandled by rememberSaveable { mutableStateOf(false) }
    val managerOpen = host.surface == PrivateMessageReplyChromeSurface.Recipients
    SideEffect { if (managerOpen) recipientEntryHandled = true }
    val activity = LocalActivity.current
    val window = windowMetrics ?: activity?.let { rememberEditorWindowMetrics(it).value }
    when {
        state.isInitializing || window == null -> Surface(modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.formError -> ReplyFormFailure(host, modifier)
        else -> CompositionLocalProvider(LocalEditorTooltipHost provides remember { EditorTooltipHost() }) {
            PrivateMessageReplyChrome(host, bodyFieldState, bodyInputTransformation, bodyScrollState,
                smileyPicker, autoOpenRecipientManager && !recipientEntryHandled, window, modifier,
                bodyCaretRevealResetKey, autoFocusBody = !autoOpenRecipientManager)
        }
    }
}

@Composable
@Suppress("LongParameterList") // Same scaffold assembly as the post, without its quote cards/header.
private fun PrivateMessageReplyChrome(
    host: PrivateMessageReplyChromeAdapter,
    body: TextFieldState,
    transformation: InputTransformation,
    scroll: ScrollState,
    smileys: SmileyPickerController,
    autoOpenRecipients: Boolean,
    window: EditorWindowMetrics,
    modifier: Modifier,
    bodyCaretRevealResetKey: () -> Int,
    autoFocusBody: Boolean,
) {
    val tooltips = requireNotNull(LocalEditorTooltipHost.current)
    val back = {
        host.back(EditorBackState(tooltipOpen = tooltips.isVisible)) {
            if (it == EditorBackAction.CloseTooltip) tooltips.dismiss()
        }
    }
    val focus = remember { FocusRequester() }
    EditorScaffold(body, window, host.title(), host.state.context, host.chrome(),
        host.barActions, host.submitState, host.submitActions, host.imageActions, host::closeRequested,
        modifier = modifier, bodyFocusRequester = focus, bodyScrollState = scroll,
        // The route's manager entry owns initial focus, including while its owner form is loading.
        // Keep this independent of the one-shot latch, which changes as soon as the manager opens.
        inputTransformation = transformation, autoFocus = autoFocusBody, surfaceFocus = host.focusMemory,
        bodyCaretRevealResetKey = bodyCaretRevealResetKey,
        onSystemBack = back,
        fieldArea = { field -> PrivateMessageReplyChromePreview(host, body, focus, field) },
        overlay = { restore, content ->
            PrivateMessageReplyChromeSurfaces(host, body, smileys, autoOpenRecipients, restore, content)
            // Last activity handler: the host resolves every ED-85 level, including bar focus return.
            BackHandler(onBack = back)
        })
}

/** Inline MP quote materialization failures keep the full-screen retry and draft offer. */
@Composable
private fun ReplyFormFailure(host: PrivateMessageReplyChromeAdapter, modifier: Modifier) {
    val state = host.state
    BackHandler { host.closeRequested() }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MessageEditorHeader(host.title(), host::closeRequested)
            if (state.source.restorableDraft != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                MessageDraftRestoreBanner(host::restoreDraft, host::discardDraft, restoreEnabled = state.restoreEnabled)
            }
            MessageFormErrorState(onRetry = host::retryFormLoad)
        }
    }
}
