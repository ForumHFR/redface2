package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.UploadProgressLabel
import fr.forumhfr.redface2.core.ui.editor.bannerText

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitButton
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitLabels
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn
import fr.forumhfr.redface2.core.ui.editor.editorControlsMaxHeight


/**
 * Post-level editor screen. Phase 2C (#145) adds a Submit button that posts the
 * reply via [PostEditorViewModel.submit]. Successful submissions raise a one-shot
 * [PostEditorEffect.SubmitSucceeded] which the navigation host translates into a
 * back navigation + topic refresh.
 */
@Composable
fun PostEditorScreen(
    request: PostEditorRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?, quotedNumreponses: List<Int>) -> Unit,
    // #604 lot 4a — pops this editor AFTER the ViewModel flushed the draft (CloseCommitted).
    // Default keeps callers without the wiring on the platform back (no flush) — `:app` wires it.
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: PostEditorViewModel = hiltViewModel<PostEditorViewModel, PostEditorViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PostEditorEffect.SubmitSucceeded ->
                    onSubmitSucceeded(effect.targetPage, effect.scrollTo, effect.quotedNumreponses)
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
        onIntent = remember(viewModel) { { intent: PostEditorIntent -> viewModel.submit(intent) } },
        // #441 — the picker is driven by the shared controller (same wiring as the MP
        // composers, cf. PrivateMessageReplyScreen) ; only SmileySelected stays an intent.
        smileyPicker = viewModel.smileyPicker,
        modifier = modifier,
    )
}

@Composable
internal fun PostEditorContent(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
) {
    var imageUrlDialogOpen by remember { mutableStateOf(false) }
    var optionsSheetOpen by remember { mutableStateOf(false) }
    val launchImagePicker = rememberEditorImagePicker(state.imagePickerMode) { event ->
        onIntent(PostEditorIntent.ImagePickerEventReceived(event))
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
                        value = state.draft,
                        onValueChange = { value -> onIntent(PostEditorIntent.ContentChanged(value)) },
                        label = stringResource(R.string.editor_field_label),
                        placeholder = stringResource(R.string.editor_field_placeholder),
                        modifier = Modifier.weight(1f),
                        // #275/#410/#447 — BTF2 owns handle scrolling and caret following in
                        // this bounded viewport. Lock while a batch upload inserts its images.
                        readOnly = state.isUploading,
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
                    ),
                    actions = EditorSubmitActions(
                        onSubmit = { onIntent(PostEditorIntent.SubmitClicked) },
                        onConfirmSubmit = { onIntent(PostEditorIntent.SubmitConfirmed) },
                        onDisarmConfirm = { onIntent(PostEditorIntent.SubmitConfirmationDismissed) },
                        onOpenOptions = { optionsSheetOpen = true },
                        onOpenSmileys = smileyPicker::open,
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
        if (imageUrlDialogOpen) {
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
                    enabled = !state.isSubmitting && !state.isLoadingForm,
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
 * #405 — non-destructive draft-restore banner. Shown when a cached draft was found on init
 * ([PostEditorState.restorableDraft] / [TopicFormState] equivalent). « Restaurer » pre-fills the
 * editor ; « Ignorer » deletes the cached row. The draft is never silently applied nor lost.
 * Shared by [PostEditorContent] and `TopicFormContent` (same string resources).
 */
@Composable
internal fun DraftRestoreBanner(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_draft_restore_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = onRestore,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.editor_draft_restore))
            }
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.editor_draft_discard))
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
    val hasAlert = state.restorableDraft != null ||
        state.submitError != null || state.uploadError != null
    LaunchedEffect(state.restorableDraft != null, state.submitError, state.uploadError) {
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
        BbcodeToolbar(
            onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
            modifier = Modifier.testTag(POST_EDITOR_TOOLBAR_TAG),
            onImageUrlRequested = onImageUrlRequested,
            onImageUploadRequested = onImageUploadRequested,
            uploading = state.isUploading,
        )
        state.uploadProgress?.let { progress ->
            UploadProgressLabel(progress)
        }
        if (state.quotes.isNotEmpty()) {
            EditorQuoteCards(
                quotes = state.quotes,
                enabled = !state.isSubmitting,
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
private fun PostEditorAlertBanners(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
) {
    if (state.restorableDraft != null) {
        DraftRestoreBanner(
            onRestore = { onIntent(PostEditorIntent.DraftRestoreRequested) },
            onDiscard = { onIntent(PostEditorIntent.DraftDiscardRequested) },
        )
    }
    state.submitError?.let { error ->
        Text(
            text = stringResource(error.bannerResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onIntent(PostEditorIntent.ErrorDismissed) }) {
            Text(text = stringResource(R.string.editor_error_dismiss))
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

/**
 * Display state of [EditorSubmitBar]. [confirmArmed] is the « confirmation avant
 * publication » flag (#312) raised by the ViewModel once the submit passed every gate and
 * the preference is on : instead of the old modal dialog, the submit button arms itself
 * (« Confirmer ? », tertiary colors) and the SECOND tap performs the real submit — less
 * intrusive, keyboard stays up. Bundled (with [EditorSubmitActions]) to stay under the
 * detekt parameter-count threshold.
 */
internal data class EditorSubmitState(
    val canSubmit: Boolean,
    val isSubmitting: Boolean,
    val isLoadingForm: Boolean,
    val confirmArmed: Boolean,
)

/**
 * Callbacks of [EditorSubmitBar]. [onOpenSmileys] is nullable : surfaces without a smiley
 * picker (the MP reply editor, for now) simply don't render the button.
 */
internal data class EditorSubmitActions(
    val onSubmit: () -> Unit,
    val onConfirmSubmit: () -> Unit,
    val onDisarmConfirm: () -> Unit,
    val onOpenOptions: () -> Unit,
    val onOpenSmileys: (() -> Unit)? = null,
)

/**
 * Bottom action bar of an editor screen, pinned above the IME so the user never has to
 * dismiss the keyboard to reach « Envoyer ». Shared by [PostEditorContent] and
 * `TopicFormContent`. Besides submit, it now carries the « Options » trigger (per-post
 * toggles moved into [EditorOptionsSheet]) and the « Smileys » trigger — reclaiming the
 * vertical space both used to take around the draft field (dogfooding feedback). The
 * armed-confirmation behaviour (#312 v2, countdown drain included) lives in the shared
 * [ArmedSubmitButton].
 */
@Composable
internal fun EditorSubmitBar(
    state: EditorSubmitState,
    actions: EditorSubmitActions,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Single bottom inset = max(navBar, ime); union() takes the larger so the two never
                    // stack into a phantom gap. Keyboard closed → bar clears the gesture nav bar; keyboard
                    // open → bar rides exactly on top of the IME. Requires
                    // windowSoftInputMode=adjustNothing on API 30+ (AndroidManifest) so the OEM does
                    // not also resize the window — the resize+imePadding double-shift was the Samsung
                    // One UI bug (#624). Below API 30, MainActivity requests adjustResize because
                    // adjustNothing dispatches a zero IME inset there; enableEdgeToEdge keeps the
                    // window unresized so the inset stays single (#1404).
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tonal containers for the secondary triggers, filled for « Envoyer » — the
                // canonical M3 emphasis pair (user choice over outlined / bare text). The
                // expressive press-morphing `shapes` overload does NOT exist on material3
                // 1.4.0 (no ButtonShapes in the artifact, verified at the bytecode) — revisit
                // when the BOM bumps material3.
                // While the confirmation is armed the secondary triggers step aside : they
                // are not actionable mid-confirmation anyway, and the freed width guarantees
                // the armed label never wraps (the tonal pills ate the Row slack and
                // line-broke « Confirmer ? » — dogfooding v108).
                if (!state.confirmArmed) {
                    FilledTonalButton(onClick = actions.onOpenOptions) {
                        Text(text = stringResource(R.string.editor_actions_options))
                    }
                    actions.onOpenSmileys?.let { openSmileys ->
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = openSmileys) {
                            Text(text = stringResource(R.string.editor_smiley_open))
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.isLoadingForm) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                ArmedSubmitButton(
                    state = ArmedSubmitState(
                        armed = state.confirmArmed,
                        enabled = state.canSubmit,
                        showProgress = state.isSubmitting,
                    ),
                    labels = ArmedSubmitLabels(
                        submit = stringResource(R.string.editor_submit),
                        confirm = stringResource(R.string.editor_submit_confirm),
                    ),
                    actions = ArmedSubmitActions(
                        onSubmit = actions.onSubmit,
                        onConfirmSubmit = actions.onConfirmSubmit,
                        onDisarm = actions.onDisarmConfirm,
                    ),
                )
            }
        }
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

private val SubmitError.bannerResId: Int
    get() = when (this) {
        is SubmitError.Hfr -> when (reason) {
            ReplyFailureReason.EmptyMessage -> R.string.editor_error_empty
            ReplyFailureReason.InvalidHashCheck -> R.string.editor_error_invalid_hash
            ReplyFailureReason.AntiFlood -> R.string.editor_error_anti_flood
            ReplyFailureReason.TopicLocked -> R.string.editor_error_topic_locked
            ReplyFailureReason.LoginRequired -> R.string.editor_error_login_required
            ReplyFailureReason.Unknown -> R.string.editor_error_unknown
        }
        SubmitError.Network -> R.string.editor_error_network
        SubmitError.SessionExpired -> R.string.editor_error_session_expired
        SubmitError.MissingSubcat -> R.string.editor_error_missing_subcat
    }

internal const val POST_EDITOR_TOOLBAR_TAG = "post-editor-toolbar"
internal const val POST_EDITOR_PREVIEW_TOGGLE_TAG = "post-editor-preview-toggle"
