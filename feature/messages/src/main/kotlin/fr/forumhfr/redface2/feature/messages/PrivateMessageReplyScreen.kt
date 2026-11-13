package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.LocalEditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.EditorDraftOfferList
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.EditorUnsavedCloseDialog
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.LocalEditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.rememberEditorWindowMetrics

/**
 * Reply editor for a private-message conversation, hosted by the shared editor chrome.
 * Submission goes through
 * [PrivateMessageReplyViewModel]; a successful send raises [PrivateMessageReplyEffect.SubmitSucceeded]
 * which the navigation host turns into a back navigation + a forced conversation reload.
 */
@Suppress("LongParameterList") // Navigation callbacks, including the editor-settings exit.
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
    onOpenEditorSettings: () -> Unit = {},
    // ED-88 (#1616) — the inline citations of this reply are durable in its draft row.
    onQuotesDelivered: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnQuotesDelivered by rememberUpdatedState(onQuotesDelivered)
    LaunchedEffect(viewModel) { viewModel.quotesDelivered.collect { currentOnQuotesDelivered() } }
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
    // Above every branch of the content, including the load failure that replaces the editor.
    if (state.showUnsavedCloseDialog) {
        EditorUnsavedCloseDialog(
            onStay = { viewModel.onUnsavedCloseDecided(leave = false) },
            onLeave = { viewModel.onUnsavedCloseDecided(leave = true) },
        )
    }
    CompositionLocalProvider(LocalEditorPreviewState provides viewModel.previewLayout) {
        PrivateMessageReplyContent(
            state, request.openRecipientManager, viewModel.bodyFieldState, viewModel.bodyInputTransformation,
            remember(viewModel) { viewModel.chromeActions() }, viewModel.smileyPicker, modifier,
            viewModel.bodyScrollState,
            viewModel.editorEvents, viewModel.chromeMemory,
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
    onOpenEditorSettings: () -> Unit = {},
) {
    val source = rememberUpdatedState(state)
    // Register the launcher before loading/errors and outside every transient surface, like the post.
    val adapter = remember { arrayOfNulls<PrivateMessageReplyChromeAdapter>(1) }
    val launchPicker = rememberEditorImagePicker(state.imagePickerMode,
        onCancelled = { adapter[0]?.pickerCancelled() }, onEvent = { adapter[0]?.picker(it) })
    val launch = rememberUpdatedState(launchPicker)
    val host = remember(bodyFieldState, chromeEvents, chromeMemory, smileyPicker, actions) {
        PrivateMessageReplyChromeAdapter({ source.value }, bodyFieldState, chromeEvents,
            actions, { launch.value() }, { smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION) }, chromeMemory,
            openSmileySearch = { smileyPicker.open(SmileyPickerOpenIntent.SEARCH) })
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
        state.formError -> ReplyFormFailure(host, smileyPicker, modifier)
        else -> CompositionLocalProvider(LocalEditorTooltipHost provides remember { EditorTooltipHost() }) {
            PrivateMessageReplyChrome(host, bodyFieldState, bodyInputTransformation, bodyScrollState,
                smileyPicker, autoOpenRecipientManager && !recipientEntryHandled, window, modifier,
                bodyCaretRevealResetKey, autoFocusBody = !autoOpenRecipientManager,
                onOpenEditorSettings = onOpenEditorSettings)
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
    onOpenEditorSettings: () -> Unit,
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
        bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
        bodyCaretRevealResetKey = bodyCaretRevealResetKey,
        onSystemBack = back,
        fieldArea = { field -> PrivateMessageReplyChromePreview(host, body, focus, field) },
        overlay = { restore, content ->
            PrivateMessageReplyChromeSurfaces(host, body, smileys, autoOpenRecipients, restore, content,
                onOpenEditorSettings)
            // Last activity handler: the host resolves every ED-85 level, including bar focus return.
            BackHandler(onBack = back)
        })
}

/**
 * ED-130/132: the form or quote GET failure replaces the editor, so this screen carries the offers
 * in line (ED-40: date, excerpt, decisions per version) and the quote remedies: « Recharger » (GET
 * only), « Ignorer la citation » after two failures, and the dormant-quote decision.
 */
@Composable
private fun ReplyFormFailure(
    host: PrivateMessageReplyChromeAdapter,
    smileys: SmileyPickerController,
    modifier: Modifier,
) {
    val state = host.state
    // The retry returns to the bare editor; the ↺ status still offers the draft (gate R1b R3, minor 3).
    LaunchedEffect(host) { host.closeLayersForFormFailure { smileys.dismiss() } }
    BackHandler { host.closeRequested() }
    val offered = state.source.restorableDraft != null
    // Several offers may outgrow the window: they scroll with the remedies. Without an offer the
    // error keeps its centered layout.
    val scroll = if (offered) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().then(scroll)) {
            MessageEditorHeader(host.title(), host::closeRequested)
            if (offered) Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 2.dp,
            ) {
                val offers = state.source.draftOffers
                if (offers.isEmpty()) {
                    MessageDraftRestoreChoice(host::restoreDraft, host::discardDraft, state.restoreEnabled,
                        Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
                } else Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.messages_draft_restore_message),
                        style = MaterialTheme.typography.bodyMedium)
                    EditorDraftOfferList(offers, host::restoreOffer, host::discardOffer,
                        state.restoreEnabled, state.scaffold.controlsEnabled)
                }
            }
            MessageFormErrorState(onRetry = host::retryFormLoad,
                retryLabel = stringResource(UiR.string.editor_action_reload),
                onIgnoreQuote = (host::ignoreQuote).takeIf { state.source.canIgnorePendingQuote },
                onResumeQuote = (host::resumeQuote).takeIf { state.source.hasDormantQuote })
        }
    }
}
