package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar

/**
 * Topic-level form screen. Live for [TopicFormMode.EditFirstPost] (Phase 2D
 * #148) and [TopicFormMode.New] (Phase 2E #149).
 *
 * Shared surface :
 * - Subject field (writable).
 * - Subcategory : read-only current label for EditFirstPost, dropdown for New.
 * - BBCode toolbar + draft field + optional preview.
 * - Per-post options (signature / smileys / email) identical to the post-level editor.
 * - Poll : if `state.pollPresent`, a sober note that mutation is not in this version.
 * - Submit button + error banner.
 */
@Composable
fun TopicFormScreen(
    request: TopicFormRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?) -> Unit,
    onNewTopicCreated: (cat: Int, subcat: Int, newTopicId: Int?, newNumreponse: Int?) -> Unit,
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
                        // Until a `write_create_topic_success_response.html`
                        // fixture lands, the repository cannot extract the new
                        // topic id from HFR's refresh URL. Surface a sober
                        // Toast so the user knows the POST succeeded before
                        // navigating back to the category listing.
                        Toast.makeText(context, newTopicCreatedFallback, Toast.LENGTH_LONG).show()
                    }
                    onNewTopicCreated(effect.cat, effect.subcat, effect.newTopicId, effect.newNumreponse)
                }
            }
        }
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
    val openSmileyPickerDescription = stringResource(R.string.editor_smiley_open_description)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
            )
            // Subcategory : the New mode exposes a real dropdown picker because
            // HFR ships no pre-selection and the wire submit rejects an empty
            // `subcat=`. Edit FP keeps a read-only label : the current dropdown
            // UX is intentionally deferred there (Phase 2D MVP only forwards
            // whatever HFR pre-selected).
            when (state.mode) {
                TopicFormMode.New -> SubcategoryDropdown(
                    choices = state.subcategoryChoices,
                    selectedSubcat = state.selectedSubcat,
                    enabled = !state.isSubmitting && !state.isLoadingForm,
                    onSelect = { id -> onIntent(TopicFormIntent.SubcatSelected(id)) },
                )
                TopicFormMode.EditFirstPost -> {
                    val selectedChoice =
                        state.subcategoryChoices.firstOrNull { it.id == state.selectedSubcat }
                    if (selectedChoice != null) {
                        Text(
                            text = stringResource(R.string.editor_topic_subcat_current, selectedChoice.label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            BbcodeToolbar(
                onAction = { onIntent(TopicFormIntent.ToolbarActionClicked(it)) },
            )
            // Phase 2F-C (#11 partial) — quick access to the smiley picker. Same placement
            // and rationale as `PostEditorScreen` : smileys are point-insertions, not
            // wrappers, so they don't fit the wrap-only `BbcodeAction` toolbar model.
            TextButton(
                onClick = { onIntent(TopicFormIntent.SmileyPickerOpened) },
                modifier = Modifier.semantics {
                    contentDescription = openSmileyPickerDescription
                },
            ) {
                Text(text = stringResource(R.string.editor_smiley_open))
            }
            BbcodeTextField(
                value = state.draft,
                onValueChange = { onIntent(TopicFormIntent.ContentChanged(it)) },
                label = stringResource(R.string.editor_field_label),
                modifier = Modifier.fillMaxWidth(),
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
            HorizontalDivider()
            TopicFormOptionsBlock(
                signatureEnabled = state.signatureEnabled,
                smileyDisabled = state.smileyDisabled,
                emailNotificationEnabled = state.emailNotificationEnabled,
                enabled = !state.isSubmitting && !state.isLoadingForm,
                onSignatureChanged = { onIntent(TopicFormIntent.ToggleSignature(it)) },
                onSmileyDisabledChanged = { onIntent(TopicFormIntent.ToggleSmileyDisabled(it)) },
                onEmailNotificationChanged = { onIntent(TopicFormIntent.ToggleEmailNotification(it)) },
            )
            if (state.pollPresent && !state.pollEditable) {
                // Honest copy : the topic has a poll, but Phase 2D #148 does
                // not edit poll fields — they are preserved verbatim on POST.
                Text(
                    text = stringResource(R.string.editor_topic_poll_readonly_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isLoadingForm) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
                Button(
                    enabled = state.canSubmit,
                    onClick = { onIntent(TopicFormIntent.SubmitClicked) },
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(text = stringResource(R.string.editor_submit))
                    }
                }
            }
        }
    }
}

/**
 * Material 3 dropdown picker over the [TopicFormSubcategoryChoice] list. The
 * `Aucune` option (`id == null`) is filtered out — the wire submit refuses
 * `subcat=""` and we don't want the user to see a choice that cannot be
 * submitted. Placeholder shown until the user picks something.
 *
 * Implemented as `OutlinedTextField + DropdownMenu` rather than
 * `ExposedDropdownMenuBox` to keep the dependency surface minimal and avoid
 * the experimental annotations that the box variant still requires.
 */
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
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_topic_subcat_picker_label)) },
            // Plain text caret instead of an Icons import : keeps the
            // dependency surface minimal (`material-icons-extended` is not on
            // this module's classpath) and works fine for a single chevron.
            trailingIcon = { Text(text = "▾") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true },
        )
        DropdownMenu(
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
