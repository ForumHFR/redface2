package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.MAX_IMAGES_PER_UPLOAD
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.UploadProgressLabel
import fr.forumhfr.redface2.core.ui.editor.bannerText

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitButton
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitLabels
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn


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
private fun PostEditorContent(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
) {
    var imageUrlDialogOpen by remember { mutableStateOf(false) }
    var optionsSheetOpen by remember { mutableStateOf(false) }
    // #459 PR2 — modern Android photo picker (no runtime permission). Multi-select variant: the
    // contract returns a (possibly empty) List<Uri> ; we hand the platform-free VM each Uri's string
    // and it reads + uploads them sequentially, inserting an [img] per success. API confirmed via
    // Context7 (androidx ActivityResultContracts.PickMultipleVisualMedia(maxItems), input
    // PickVisualMediaRequest, output List<Uri>). A single pick is just a one-element list.
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD),
    ) { uris ->
        if (uris.isNotEmpty()) onIntent(PostEditorIntent.ImagesPicked(uris.map { it.toString() }))
    }
    // Reply (#145), Quote (#146) and Edit (#147) submit through HFR's reply/edit form ; the other
    // (defensive) modes show a disabled note instead of a submit bar.
    val showSubmitBar = state.mode == PostEditorMode.Reply || state.mode == PostEditorMode.Edit
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // No outer scroll : the draft field is weighted so it stretches to fill every
            // free pixel down to the bottom bar (dogfooding v108 — the column used to leave
            // a large blank under « Afficher l'aperçu »). Long content scrolls in the field's
            // own fillViewport column (#275/#410) and inside the preview pane, which is also
            // why weight() is usable at all — it needs the bounded height an outer
            // verticalScroll would destroy. Keyboard handling : the bar's IME inset grows,
            // this column shrinks by the same amount (weight absorbs), and the field's
            // viewport re-anchors the cursor line.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(state.mode.titleResId),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                BbcodeToolbar(
                    onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
                    onImageUrlRequested = { imageUrlDialogOpen = true },
                    onImageUploadRequested = {
                        pickImagesLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    uploading = state.isUploading,
                )

                // Multi-image upload — « n/N » progress under the toolbar while a batch (> 1 image)
                // is in flight. A single upload keeps uploadProgress null (toolbar spinner only).
                UploadProgressLabel(state.uploadProgress)

                // #604 lot 3 (mockup P3) — the armed citations as cards ABOVE the field, the same
                // rendering as the quick-reply sheet : the field only ever holds the user's text,
                // the [quotemsg] blocks are materialised at submit.
                // #555 — everything that competes with the field for vertical space (draft
                // banner, error banners, cards) lives in ONE top zone that scrolls past its
                // budget : the field keeps EDITOR_FIELD_MIN_HEIGHT no matter how short the
                // IME leaves the window. Before this, the field was the only weighted child
                // and fixed content could crush it to zero pixels on a short display (thibw).
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val topZoneMaxHeight = editorTopZoneMaxHeight(available = maxHeight)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorTopZone(
                            state = state,
                            onIntent = onIntent,
                            maxHeight = topZoneMaxHeight,
                        )

                        BbcodeTextField(
                            value = state.draft,
                            onValueChange = { value -> onIntent(PostEditorIntent.ContentChanged(value)) },
                            label = stringResource(R.string.editor_field_label),
                            placeholder = stringResource(R.string.editor_field_placeholder),
                            modifier = Modifier.weight(1f),
                            // #275/#410 — grow-with-content field in its own scrollable viewport so
                            // the cursor stays visible under the IME (typing AND refocus after the
                            // preview).
                            fillViewport = true,
                            // Multi-image upload — lock editing during a batch so the user can't
                            // move the caret between two programmatic [img] insertions (keeps them
                            // in pick order).
                            readOnly = state.isUploading,
                            // #555 — the editor opens ready to type: focus + IME on entry. Critical
                            // in edit mode (field hydrated with a long post: nothing set the focus,
                            // keyboard closed, #447 caret-follow inert) ; for a reply it is the
                            // expected behaviour anyway.
                            autoFocus = true,
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
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
                    // The preview shares the stretch with the field (50/50) and scrolls
                    // internally — long rendered content must not push the bar off-screen.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        BbcodePreview(content = state.preview)
                    }
                }

                if (!showSubmitBar) {
                    // Defensive fallback for future post-level modes. Reply (#145),
                    // Quote (#146) and Edit (#147) submit through the bottom bar ;
                    // topic-level create/edit flows are handled by TopicFormScreen.
                    Text(
                        text = stringResource(R.string.editor_submit_disabled),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        // HFR per-post option toggles, moved behind the bottom bar's « Options » trigger.
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
                    enabled = !state.isSubmitting && !state.isLoadingForm,
                    onSignatureChanged = { onIntent(PostEditorIntent.ToggleSignature(it)) },
                    onSmileyDisabledChanged = { onIntent(PostEditorIntent.ToggleSmileyDisabled(it)) },
                    onEmailNotificationChanged = { onIntent(PostEditorIntent.ToggleEmailNotification(it)) },
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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.editor_draft_restore_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore) {
                    Text(text = stringResource(R.string.editor_draft_restore))
                }
                TextButton(onClick = onDiscard) {
                    Text(text = stringResource(R.string.editor_draft_discard))
                }
            }
        }
    }
}

/**
 * #555 — the editor's TOP ZONE : everything that competes with the draft field for vertical
 * space (draft-restore banner, submit/upload error banners, quote cards) in one scrollable
 * column bounded by [maxHeight] (the [editorTopZoneMaxHeight] budget). Scrolling past the
 * budget keeps every element reachable while the field keeps its guaranteed minimum below.
 */
@Composable
private fun EditorTopZone(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    maxHeight: Dp,
) {
    // Gate Codex — an alert must not appear below the zone's internal fold : snap the zone
    // back to the top whenever a banner (draft, submit, upload) shows up, so it is the first
    // thing in the viewport.
    val scroll = rememberScrollState()
    val hasAlert = state.restorableDraft != null ||
        state.submitError != null || state.uploadError != null
    LaunchedEffect(hasAlert) {
        if (hasAlert) scroll.animateScrollTo(0)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .heightIn(max = maxHeight)
            .verticalScroll(scroll),
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
        EditorQuoteCards(
            quotes = state.quotes,
            enabled = !state.isSubmitting,
            onIntent = onIntent,
        )
    }
}

/**
 * #604 lot 3 (mockup P3) — the quote cards block of the full-screen editor : the shared
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
    // No early-return on empty (#604 lot 4a) : the shared column hosts the live region that
    // announces the LAST removal — hiding the whole block would silence it.
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
 * #555 — the top-zone budget of the editor : the draft banner, error banners and quote cards
 * share whatever the available height leaves ABOVE the field's guaranteed minimum, bounded by
 * [EDITOR_TOP_ZONE_MAX_HEIGHT] on a roomy display. Pure — pinned by unit test.
 */
internal fun editorTopZoneMaxHeight(available: Dp): Dp =
    (available - EDITOR_FIELD_MIN_HEIGHT - EDITOR_ZONE_SPACING)
        .coerceIn(0.dp, EDITOR_TOP_ZONE_MAX_HEIGHT)

// #555 — « Tout vider » + ~4 one-line cards (the historical 240dp cards budget) + room for a
// draft/error banner. Past that the top zone scrolls.
internal val EDITOR_TOP_ZONE_MAX_HEIGHT = 360.dp

// #555 — the draft field never shrinks below this, whatever the IME + top zone demand.
internal val EDITOR_FIELD_MIN_HEIGHT = 96.dp

// Spacing between the top zone and the field inside their shared weighted box.
private val EDITOR_ZONE_SPACING = 12.dp

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
                    // open → bar rides exactly on top of the IME. Requires windowSoftInputMode=adjustNothing
                    // (AndroidManifest) so the OEM does NOT also resize the window — the resize+imePadding
                    // double-shift was the Samsung One UI bug (#624).
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
@Suppress("LongParameterList") // 3 toggles + 3 callbacks + enabled — each call-site distinct.
private fun PostEditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
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
