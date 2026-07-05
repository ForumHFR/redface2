package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.MAX_IMAGES_PER_UPLOAD
import fr.forumhfr.redface2.core.ui.editor.UploadProgressLabel
import fr.forumhfr.redface2.core.ui.editor.bannerText

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet

/**
 * Topic-level form screen. Live for [TopicFormMode.EditFirstPost] (Phase 2D
 * #148) and [TopicFormMode.New] (Phase 2E #149).
 *
 * Shared surface :
 * - Subject field (writable).
 * - Subcategory dropdown for both EditFirstPost and New.
 * - BBCode toolbar + draft field + optional preview.
 * - Per-post options (signature / smileys / email) identical to the post-level editor.
 * - Poll : if `state.pollPresent`, a sober note that mutation is not in this version.
 * - Submit button + error banner.
 */
@Composable
@Suppress("LongParameterList") // One callback per navigation outcome — each wired to a distinct :app pop.
fun TopicFormScreen(
    request: TopicFormRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?) -> Unit,
    // #206 workaround — `subject` is the exact posted title, forwarded to the category
    // listing so it can highlight the freshly-created row (HFR never returns the new id).
    onNewTopicCreated: (cat: Int, subcat: Int, newTopicId: Int?, newNumreponse: Int?, subject: String) -> Unit,
    // #803 pattern (state-hygiene audit 2026-07-05) — pops this form AFTER the ViewModel flushed
    // the draft (CloseCommitted). Default keeps callers without the wiring on the platform back
    // (no flush) — `:app` wires it. Mirrors PostEditorScreen.onClose.
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: TopicFormViewModel = hiltViewModel<TopicFormViewModel, TopicFormViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val newTopicCreatedFallback = stringResource(R.string.editor_new_topic_created)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TopicFormEffect.SubmitSucceeded ->
                    onSubmitSucceeded(effect.targetPage, effect.scrollTo)
                is TopicFormEffect.NewTopicCreated -> {
                    if (effect.newTopicId == null) {
                        // Fallback path (#206, the only real path): the create succeeded but
                        // HFR's refresh URL carried no `sujet_{id}_{page}` segment, so the
                        // repository could not extract the new topic id (confirmed always the
                        // case for create — #214). Surface a sober Toast so the user knows the
                        // POST went through before landing back on the category listing, which
                        // will highlight the freshly-created row by exact-title match.
                        Toast.makeText(context, newTopicCreatedFallback, Toast.LENGTH_LONG).show()
                    }
                    onNewTopicCreated(
                        effect.cat,
                        effect.subcat,
                        effect.newTopicId,
                        effect.newNumreponse,
                        effect.subject,
                    )
                }
                TopicFormEffect.CloseCommitted -> onClose?.invoke()
            }
        }
    }

    // #803 pattern — route the system back through the ViewModel so the pending autosave debounce
    // is flushed BEFORE the pop (trading the predictive-back preview for never losing the last
    // < 750 ms of typing — same trade-off as PostEditorScreen). Only armed when `:app` wired the pop.
    if (onClose != null) {
        BackHandler { viewModel.submit(TopicFormIntent.CloseRequested) }
    }

    TopicFormContent(
        state = state,
        onIntent = viewModel::submit,
        modifier = modifier,
    )

    // Sheet hoisted as a sibling of the scrollable content : if it lived inside
    // the `Column.verticalScroll`, the bottom sheet would get squashed by the
    // scroll container's measurement. Same rationale as `PostEditorScreen`.
    val pickerState = state.smileyPicker
    if (pickerState is SmileyPickerState.Open) {
        SmileyPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.submit(TopicFormIntent.SmileyPickerDismissed) },
            onQueryChange = { viewModel.submit(TopicFormIntent.SmileySearchQueryChanged(it)) },
            onSmileyClicked = { viewModel.submit(TopicFormIntent.SmileySelected(it)) },
        )
    }
}

@Composable
internal fun TopicFormContent(
    state: TopicFormState,
    onIntent: (TopicFormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var imageUrlDialogOpen by remember { mutableStateOf(false) }
    var optionsSheetOpen by remember { mutableStateOf(false) }
    // #459 — modern photo picker (no runtime permission), same contract as PostEditorContent:
    // multi-select returns a (possibly empty) List<Uri>, handed to the VM as Uri strings.
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD),
    ) { uris ->
        if (uris.isNotEmpty()) onIntent(TopicFormIntent.ImagesPicked(uris.map { it.toString() }))
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(state.mode.titleResId),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = { onIntent(TopicFormIntent.SubjectChanged(it)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSubmitting,
                    label = { Text(stringResource(R.string.editor_topic_subject_label)) },
                    // #237 — sentence capitalization (parité RF1) like the body field.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                // #213 — a category WITHOUT a sub-category (e.g. IA, cat=32) renders no
                // `<select name=subcat>` on HFR's form (`hasSubcategorySelect = false`), so
                // posting there uses subcat=0. Hide the picker entirely in that case rather
                // than showing an empty « choisir une sous-catégorie » dropdown the user
                // cannot act on (dogfood feedback @XaaT). Categories WITH sub-categories keep it.
                if (state.hasSubcategorySelect) {
                    SubcategoryDropdown(
                        choices = state.subcategoryChoices,
                        selectedSubcat = state.selectedSubcat,
                        enabled = !state.isSubmitting && !state.isLoadingForm,
                        onSelect = { id -> onIntent(TopicFormIntent.SubcatSelected(id)) },
                    )
                }
                BbcodeToolbar(
                    onAction = { onIntent(TopicFormIntent.ToolbarActionClicked(it)) },
                    onImageUrlRequested = { imageUrlDialogOpen = true },
                    // #459 — upload wiring, same affordance as the reply editor.
                    onImageUploadRequested = {
                        pickImagesLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    uploading = state.isUploading,
                )
                // #459 — « n/N » batch counter while a multi-image upload is in flight.
                UploadProgressLabel(state.uploadProgress)
                BbcodeTextField(
                    value = state.draft,
                    onValueChange = { onIntent(TopicFormIntent.ContentChanged(it)) },
                    label = stringResource(R.string.editor_field_label),
                    modifier = Modifier.fillMaxWidth(),
                    // #459 — lock editing during a batch so the caret cannot move between two
                    // programmatic [img] insertions (keeps them in pick order).
                    readOnly = state.isUploading,
                )
                TextButton(onClick = { onIntent(TopicFormIntent.TogglePreview) }) {
                    Text(
                        text = if (state.isPreviewVisible) {
                            stringResource(R.string.editor_preview_hide)
                        } else {
                            stringResource(R.string.editor_preview_show)
                        },
                    )
                }
                if (state.isPreviewVisible) {
                    BbcodePreview(content = state.preview, modifier = Modifier.fillMaxWidth())
                }
                if (state.pollPresent && !state.pollEditable) {
                    // Honest copy : the topic has a poll, but Phase 2D #148 does
                    // not edit poll fields — they are preserved verbatim on POST.
                    Text(
                        text = stringResource(R.string.editor_topic_poll_readonly_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.restorableDraft != null || state.restorableSubject != null) {
                    DraftRestoreBanner(
                        onRestore = { onIntent(TopicFormIntent.DraftRestoreRequested) },
                        onDiscard = { onIntent(TopicFormIntent.DraftDiscardRequested) },
                    )
                }
                TopicFormErrorBanners(state = state, onIntent = onIntent)
            }
            // Send-button accessibility — pin « Envoyer » to the bottom, above the IME, so the user
            // never has to dismiss the keyboard to submit a new topic / first-post edit (shared
            // EditorSubmitBar with PostEditorScreen).
            EditorSubmitBar(
                state = EditorSubmitState(
                    canSubmit = state.canSubmit,
                    isSubmitting = state.isSubmitting,
                    isLoadingForm = state.isLoadingForm,
                    confirmArmed = state.showSubmitConfirmation,
                ),
                actions = EditorSubmitActions(
                    onSubmit = { onIntent(TopicFormIntent.SubmitClicked) },
                    onConfirmSubmit = { onIntent(TopicFormIntent.SubmitConfirmed) },
                    onDisarmConfirm = { onIntent(TopicFormIntent.SubmitConfirmationDismissed) },
                    onOpenOptions = { optionsSheetOpen = true },
                    onOpenSmileys = { onIntent(TopicFormIntent.SmileyPickerOpened) },
                ),
            )
        }
    }
    // HFR per-topic option toggles, moved behind the bottom bar's « Options » trigger
    // (shared EditorOptionsSheet with PostEditorScreen).
    if (optionsSheetOpen) {
        EditorOptionsSheet(onDismiss = { optionsSheetOpen = false }) {
            TopicFormOptionsBlock(
                signatureEnabled = state.signatureEnabled,
                smileyDisabled = state.smileyDisabled,
                emailNotificationEnabled = state.emailNotificationEnabled,
                enabled = !state.isSubmitting && !state.isLoadingForm,
                onSignatureChanged = { onIntent(TopicFormIntent.ToggleSignature(it)) },
                onSmileyDisabledChanged = { onIntent(TopicFormIntent.ToggleSmileyDisabled(it)) },
                onEmailNotificationChanged = { onIntent(TopicFormIntent.ToggleEmailNotification(it)) },
            )
        }
    }
    if (imageUrlDialogOpen) {
        ImageUrlDialog(
            onDismiss = { imageUrlDialogOpen = false },
            onInsert = { url -> onIntent(TopicFormIntent.ImageUrlInserted(url)) },
        )
    }
}

/**
 * Dismissible error banners of the topic composer: the submit failure (typed [SubmitError]) and the
 * #459 upload failure (typed `UploadError`, same rendering as `PostEditorContent`). Extracted so
 * [TopicFormContent] stays under detekt's cyclomatic-complexity budget (same rationale as
 * `UploadProgressLabel`).
 */
@Composable
private fun TopicFormErrorBanners(
    state: TopicFormState,
    onIntent: (TopicFormIntent) -> Unit,
) {
    state.submitError?.let { error ->
        Text(
            text = stringResource(error.bannerResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onIntent(TopicFormIntent.ErrorDismissed) }) {
            Text(text = stringResource(R.string.editor_error_dismiss))
        }
    }
    state.uploadError?.let { error ->
        Text(
            text = error.bannerText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onIntent(TopicFormIntent.UploadErrorDismissed) }) {
            Text(text = stringResource(R.string.editor_error_dismiss))
        }
    }
}

/**
 * Material 3 dropdown picker over the [TopicFormSubcategoryChoice] list. The
 * `Aucune` option (`id == null`) is filtered out — the wire submit refuses
 * `subcat=""` and we don't want the user to see a choice that cannot be
 * submitted. Placeholder shown until the user picks something.
 *
 * Implemented with Material 3 `ExposedDropdownMenuBox` so the read-only field
 * remains reliably tappable and accessible in both New and EditFirstPost modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubcategoryDropdown(
    choices: List<fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice>,
    selectedSubcat: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val pickable = remember(choices) { choices.filter { it.id != null } }
    val placeholder = stringResource(R.string.editor_topic_subcat_picker_placeholder)
    val selectedLabel = pickable.firstOrNull { it.id == selectedSubcat }?.label ?: placeholder
    var expanded by remember { mutableStateOf(false) }
    val menuEnabled = enabled && pickable.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = menuEnabled && it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_topic_subcat_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = menuEnabled),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            pickable.forEach { choice ->
                val id = choice.id ?: return@forEach
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // state-hoisted Composable
private fun TopicFormOptionsBlock(
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
        OptionToggleRow(
            label = stringResource(R.string.editor_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggleRow(
            label = stringResource(R.string.editor_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggleRow(
            label = stringResource(R.string.editor_option_email_notification),
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
    }
}

@Composable
private fun OptionToggleRow(
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

private val TopicFormMode.titleResId: Int
    get() = when (this) {
        TopicFormMode.New -> R.string.editor_topic_new_title
        TopicFormMode.EditFirstPost -> R.string.editor_topic_edit_first_post_title
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
