package fr.forumhfr.redface2.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EditorSubmissionSummary
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadProgressLabel
import fr.forumhfr.redface2.core.ui.editor.bannerText
import fr.forumhfr.redface2.core.ui.editor.editorControlsMaxHeight
import fr.forumhfr.redface2.core.ui.editor.hasSubmissionSummary
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker

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
    // Default keeps callers without the wiring on the platform back (no flush) — `:app` wires it.
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
    val currentOnSubmitSucceeded by rememberUpdatedState(onSubmitSucceeded)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PostEditorEffect.SubmitSucceeded ->
                    currentOnSubmitSucceeded(
                        effect.targetPage, effect.scrollTo, effect.quotedNumreponses, effect.operation,
                    )
                PostEditorEffect.CloseCommitted -> onClose?.invoke()
            }
        }
    }
    // #604 lot 4a — route the system back through the ViewModel so the pending autosave debounce
    // is flushed BEFORE the pop (trading the predictive-back preview for never losing the last
    // < 750 ms of typing — cadrage Codex, item 2). Only armed when `:app` wired the pop.
    if (onClose != null) {
        BackHandler { viewModel.submit(PostEditorIntent.CloseRequested) }
    }
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
    )
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
) {
    var imageUrlDialogOpen by remember { mutableStateOf(false) }
    var optionsSheetOpen by remember { mutableStateOf(false) }
    val launchImagePicker = rememberEditorImagePicker(state.imagePickerMode) { event ->
        onIntent(PostEditorIntent.ImagePickerEventReceived(event))
    }
    if (state.isInitializing) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    // Reply (#145), Quote (#146) and Edit (#147) submit through HFR's reply/edit form ; the other
    // (defensive) modes show a disabled note instead of a submit bar.
    val showSubmitBar = state.mode == PostEditorMode.Reply || state.mode == PostEditorMode.Edit
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // #447 — the bounded budget covers ALL editor chrome, not just alerts/cards. The
            // weighted BTF2 field therefore keeps its 160 dp reserve on a short IME viewport;
            // title, toolbar and preview remain reachable in the controls zone above it.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val controlsMaxHeight = editorControlsMaxHeight(
                    available = maxHeight,
                    fieldMin = EDITOR_DRAFT_MIN_HEIGHT,
                    // An open preview may use every pixel left above the 160 dp draft instead of
                    // staying capped at 360 dp on roomy windows.
                    allowRoomyExpansion = state.isPreviewVisible,
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PostEditorControlsZone(
                        state = state,
                        onIntent = onIntent,
                        maxHeight = controlsMaxHeight,
                        onImageUrlRequested = { imageUrlDialogOpen = true },
                        onImageUploadRequested = launchImagePicker,
                        showSubmitBar = showSubmitBar,
                    )
                    BbcodeTextField(
                        state = bodyFieldState,
                        inputTransformation = bodyInputTransformation,
                        scrollState = bodyScrollState,
                        label = stringResource(R.string.editor_field_label),
                        placeholder = stringResource(R.string.editor_field_placeholder),
                        modifier = Modifier.weight(1f),
                        // #275/#410/#447 — BTF2 owns handle scrolling and caret following in
                        // this bounded viewport. Lock while a batch upload inserts its images.
                        readOnly = state.isAwaitingInitialBody ||
                            state.isUploading || state.submission.blocksBodyMutation,
                        // #555 — the editor opens ready to type, including hydrated edit drafts.
                        autoFocus = true,
                    )
                }
            }
            // Send-button accessibility — the submit action is pinned to the bottom of the screen
            // (not buried at the end of the scrolled column) and lifted above the IME so the user
            // never has to dismiss the keyboard to reach « Envoyer ». Only Reply / Edit submit here;
            // the defensive fallback mode keeps its disabled note inside the scroll.
            if (showSubmitBar) {
                EditorSubmitBar(
                    state = EditorSubmitState(
                        canSubmit = state.canSubmit,
                        isSubmitting = state.isSubmitting,
                        isLoadingForm = state.isLoadingForm,
                        confirmArmed = state.showSubmitConfirmation,
                        armToken = state.armToken,
                        controlsEnabled = !state.submission.blocksBodyMutation,
                    ),
                    actions = EditorSubmitActions(
                        onSubmit = { timeout -> onIntent(PostEditorIntent.SubmitClicked(timeout)) },
                        onConfirmSubmit = { onIntent(PostEditorIntent.SubmitConfirmed(state.armToken)) },
                        onDisarmConfirm = { onIntent(PostEditorIntent.SubmitConfirmationDismissed) },
                        onOpenOptions = { optionsSheetOpen = true },
                        onOpenSmileys = (smileyPicker::open).takeUnless { state.isAwaitingInitialBody },
                    ),
                )
            }
        }
        // Phase 2F-B (#11) — bottom-sheet smiley picker. Rendered as a sibling of the
        // Column so the sheet can scrim the editor without being constrained by the
        // verticalScroll above. #441 — visibility is owned by the shared
        // SmileyPickerController ; dismissal goes through the controller (which cancels
        // any in-flight wiki search and snapshots the search for the #824 restore), while
        // the insertion stays an MVI intent (draft mutation).
        val pickerState by smileyPicker.state.collectAsStateWithLifecycle()
        (pickerState as? SmileyPickerState.Open)?.let { picker ->
            SmileyPickerSheet(
                state = picker,
                onDismiss = smileyPicker::dismiss,
                onQueryChange = smileyPicker::onQueryChanged,
                onSmileyClicked = { token -> onIntent(PostEditorIntent.SmileySelected(token)) },
            )
        }
        if (imageUrlDialogOpen && !state.isAwaitingInitialBody) {
            ImageUrlDialog(
                onDismiss = { imageUrlDialogOpen = false },
                onInsert = { url -> onIntent(PostEditorIntent.ImageUrlInserted(url)) },
            )
        }
        // HFR per-post controls, moved behind the bottom bar's « Options » trigger.
        // Defaults come from `ReplyForm.options` (the `checked` attribute of each HTML
        // checkbox HFR rendered for this user / topic) ; the repository only adds the
        // matching POST field when the toggle is on — mirroring how a browser submits.
        // Phase 2D (#147) — Edit shares the same options surface as Reply.
        if (optionsSheetOpen) {
            EditorOptionsSheet(onDismiss = { optionsSheetOpen = false }) {
                PostEditorOptions(
                    signatureEnabled = state.signatureEnabled,
                    smileyDisabled = state.smileyDisabled,
                    emailNotificationEnabled = state.emailNotificationEnabled,
                    msgIcon = state.msgIcon,
                    enabled = !state.submission.blocksBodyMutation && !state.isLoadingForm,
                    onSignatureChanged = { onIntent(PostEditorIntent.ToggleSignature(it)) },
                    onSmileyDisabledChanged = { onIntent(PostEditorIntent.ToggleSmileyDisabled(it)) },
                    onEmailNotificationChanged = { onIntent(PostEditorIntent.ToggleEmailNotification(it)) },
                    onMsgIconSelected = { onIntent(PostEditorIntent.MsgIconSelected(it)) },
                )
            }
        }
    }
}

/**
 * #447/#555 — every non-draft child lives in this one bounded scroller. Alerts are emitted first,
 * then title/toolbar, quote cards and preview. The preview deliberately scrolls here instead of
 * sharing field weight: showing it can no longer take pixels from the 160 dp draft reserve.
 */
@Composable
@Suppress("LongParameterList") // One callback per toolbar action plus the shared editor state.
private fun PostEditorControlsZone(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    maxHeight: Dp,
    onImageUrlRequested: () -> Unit,
    onImageUploadRequested: () -> Unit,
    showSubmitBar: Boolean,
) {
    val scroll = rememberScrollState()
    val hasAlert = state.legacyQuoteRequestDropped || state.restorableDraft != null ||
        state.submitError != null || state.uploadError != null
    LaunchedEffect(
        state.legacyQuoteRequestDropped,
        state.restorableDraft != null,
        state.submitError,
        state.uploadError,
    ) {
        if (hasAlert) scroll.animateScrollTo(0)
    }
    LaunchedEffect(state.isPreviewVisible) {
        if (state.isPreviewVisible) scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .heightIn(max = maxHeight)
            .verticalScroll(scroll),
    ) {
        PostEditorAlertBanners(state = state, onIntent = onIntent)
        Text(
            text = stringResource(state.mode.titleResId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!state.isAwaitingInitialBody) {
            BbcodeToolbar(
                onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
                modifier = Modifier.testTag(POST_EDITOR_TOOLBAR_TAG),
                onImageUrlRequested = onImageUrlRequested,
                onImageUploadRequested = onImageUploadRequested,
                uploading = state.isUploading,
            )
        }
        state.uploadProgress?.let { progress ->
            UploadProgressLabel(progress)
        }
        if (state.quotes.isNotEmpty()) {
            EditorQuoteCards(
                quotes = state.quotes,
                enabled = !state.submission.blocksBodyMutation,
                onIntent = onIntent,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(POST_EDITOR_PREVIEW_TOGGLE_TAG),
            contentAlignment = Alignment.CenterStart,
        ) {
            TextButton(onClick = { onIntent(PostEditorIntent.TogglePreview) }) {
                Text(
                    text = stringResource(
                        if (state.isPreviewVisible) {
                            R.string.editor_preview_hide
                        } else {
                            R.string.editor_preview_show
                        },
                    ),
                )
            }
        }
        if (state.isPreviewVisible) {
            HorizontalDivider()
            BbcodePreview(content = state.preview, modifier = Modifier.fillMaxWidth())
        }
        if (!showSubmitBar) {
            Text(
                text = stringResource(R.string.editor_submit_disabled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // One independent block per banner ; flat by design.
internal fun PostEditorAlertBanners(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
) {
    val quoteAwaitingRestore = state.restorableDraft != null && state.pendingQuotes.isNotEmpty() &&
        !state.hasDormantQuote
    if (state.restorableDraft != null) {
        DraftRestoreBanner(
            onRestore = { onIntent(PostEditorIntent.DraftRestoreRequested) },
            onDiscard = { onIntent(PostEditorIntent.DraftDiscardRequested) },
            restoreEnabled = state.canRestoreDraft && !state.isUploading && !state.submission.blocksBodyMutation,
        )
        if (quoteAwaitingRestore) {
            Text(stringResource(R.string.editor_quote_pending), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.legacyQuoteRequestDropped) {
        Text(
            text = stringResource(R.string.editor_legacy_quote_dropped),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onIntent(PostEditorIntent.LegacyQuoteNoticeDismissed) }) {
            Text(stringResource(R.string.editor_error_dismiss))
        }
    }
    state.submitError?.let { error ->
        val summary = error.takeIf { it.hasSubmissionSummary }
        if (summary != null) {
            EditorSubmissionSummary(summary)
            Text(
                text = error.bannerText(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = if (!state.unreadableQuoteRequest && error.cause == EditorCause.QuoteFetchFailed &&
                    state.canIgnorePendingQuote
                ) {
                    stringResource(fr.forumhfr.redface2.core.ui.R.string.editor_error_quote_ignore)
                } else {
                    error.bannerText()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val reloadForError = !quoteAwaitingRestore &&
            (state.pendingQuotes.isNotEmpty() || error.action == EditorErrorAction.Reload)
        // A blank unresolved attempt can preserve its uncertainty banner after the form GET failed.
        val reload = state.isAwaitingInitialBody || state.quoteStorageUnavailable || reloadForError
        if (!state.hasDormantQuote || state.quoteStorageUnavailable) {
            val action = if (reload) PostEditorIntent.ReloadRequested else PostEditorIntent.ErrorDismissed
            val label = if (reload) {
                fr.forumhfr.redface2.core.ui.R.string.editor_action_reload
            } else { R.string.editor_error_dismiss }
            TextButton(onClick = { onIntent(action) }, enabled = !state.isLoadingForm) {
                Text(text = stringResource(label))
            }
        }
    }
    // ED-132: these decisions belong to the request/attempt, independently of the visible error.
    if (state.hasDormantQuote) {
        TextButton(
            onClick = { onIntent(PostEditorIntent.PendingQuoteResumed) },
            enabled = !state.isLoadingForm && !state.submission.blocksBodyMutation,
        ) {
            Text(stringResource(R.string.editor_action_resume_quote))
        }
    }
    if (state.canIgnorePendingQuote) {
        TextButton(onClick = { onIntent(PostEditorIntent.PendingQuoteIgnored) }) {
            Text(stringResource(fr.forumhfr.redface2.core.ui.R.string.editor_action_ignore_quote))
        }
    }
    state.uploadError?.let { error ->
        Text(
            text = error.bannerText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onIntent(PostEditorIntent.UploadErrorDismissed) }) {
            Text(text = stringResource(R.string.editor_error_dismiss))
        }
    }
}

/**
 * #604 lot 3 (mockup P3) — the non-empty quote cards block of the full-screen editor : the shared
 * [QuoteCard] rendering plus « Tout vider » (#436, shown from two cards up — for one card the
 * per-card ✕ is the same act). Deliberately UNBOUNDED here : the block lives inside the
 * editor's budgeted top zone (#555), whose single scroll keeps every card reachable.
 */
@Composable
private fun EditorQuoteCards(
    quotes: List<QuoteSelection>,
    enabled: Boolean,
    onIntent: (PostEditorIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (quotes.size > 1) {
            val clearAllLabel = stringResource(R.string.editor_quotes_clear_all_a11y)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = { onIntent(PostEditorIntent.QuotesCleared) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = clearAllLabel },
                ) {
                    Text(text = stringResource(R.string.editor_quotes_clear_all))
                }
            }
        }
        QuoteCardsColumn(
            quotes = quotes,
            enabled = enabled,
            callbacks = QuoteCardsCallbacks(
                onMoveUp = { numreponse -> onIntent(PostEditorIntent.QuoteMoved(numreponse, delta = -1)) },
                onMoveDown = { numreponse -> onIntent(PostEditorIntent.QuoteMoved(numreponse, delta = 1)) },
                onRemove = { numreponse -> onIntent(PostEditorIntent.QuoteRemoved(numreponse)) },
            ),
        )
    }
}

@Composable
@Suppress("LongParameterList") // HFR options + callbacks + enabled — each is user-editable.
internal fun PostEditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    msgIcon: Int,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
    onMsgIconSelected: (Int) -> Unit,
    expandedTone: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_options_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_email_notification),
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
        MessageIconPicker(
            selectedIcon = msgIcon,
            enabled = enabled,
            onIconSelected = onMsgIconSelected,
            alwaysExpanded = expandedTone,
        )
    }
}

@Composable
private fun OptionToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private val PostEditorMode.titleResId: Int
    get() = when (this) {
        PostEditorMode.Reply -> R.string.editor_post_reply_title
        PostEditorMode.Edit -> R.string.editor_post_edit_title
    }

internal const val POST_EDITOR_TOOLBAR_TAG = "post-editor-toolbar"
internal const val POST_EDITOR_PREVIEW_TOGGLE_TAG = "post-editor-preview-toggle"
