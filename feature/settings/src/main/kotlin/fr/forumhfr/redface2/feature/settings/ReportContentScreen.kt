package fr.forumhfr.redface2.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitButton
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitLabels
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState

@Composable
fun ReportContentScreen(
    build: ReportBuildInfo,
    onEffect: (ReportContentEffect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<ReportContentViewModel, ReportContentViewModel.Factory>(
        creationCallback = { it.create(build) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.effects.collect(onEffect) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (viewModel.state.value.requiresLogin) {
            viewModel.onIntent(ReportContentIntent.RetryForm)
        }
    }
    BackHandler { viewModel.onIntent(ReportContentIntent.Close) }
    ReportContent(state, viewModel::onIntent, modifier)
}

@Composable
internal fun ReportContent(
    state: ReportContentUiState,
    onIntent: (ReportContentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_about_report),
                onBack = { onIntent(ReportContentIntent.Close) },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            ReportScrollableContent(state, onIntent, maxHeight)
        }
    }
}

@Composable
private fun ReportScrollableContent(
    state: ReportContentUiState,
    onIntent: (ReportContentIntent) -> Unit,
    maxFieldHeight: Dp,
) {
    // Keep the send action in the scroll so it cannot squeeze the focused field above the IME.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.report_delivery), style = MaterialTheme.typography.bodyLarge)
        when (state.session) {
            ReportSession.CHECKING -> {
                Text(stringResource(R.string.report_checking_session))
                CircularProgressIndicator()
            }
            ReportSession.ANONYMOUS -> ReportAnonymousActions(onIntent)
            ReportSession.AUTHENTICATED -> {
                ReportForm(state, onIntent, maxFieldHeight)
                ReportFormStatus(state, onIntent)
                ReportSubmitButton(state, onIntent)
            }
        }
    }
}

@Composable
private fun ReportAnonymousActions(onIntent: (ReportContentIntent) -> Unit) {
    Text(stringResource(R.string.report_login_explanation))
    Button(onClick = { onIntent(ReportContentIntent.Login) }) { Text(stringResource(R.string.report_login)) }
    TextButton(onClick = { onIntent(ReportContentIntent.Email) }) {
        Column {
            Text(stringResource(R.string.report_email))
            Text(stringResource(R.string.report_email_external), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReportForm(
    state: ReportContentUiState,
    onIntent: (ReportContentIntent) -> Unit,
    maxFieldHeight: Dp,
) {
    Text(stringResource(R.string.report_identity_notice), style = MaterialTheme.typography.bodyMedium)
    ReportReasons(state, onIntent)
    OutlinedTextField(
        value = state.draft.description,
        onValueChange = { onIntent(ReportContentIntent.DescriptionChanged(it)) },
        label = { Text(stringResource(R.string.report_description)) },
        enabled = state.canEdit,
        minLines = 3,
        maxLines = 6,
        // Bound the viewport while preserving a usable input line on very short IME viewports.
        modifier = Modifier.fillMaxWidth().heightIn(max = maxFieldHeight.coerceAtLeast(96.dp)),
    )
    OutlinedTextField(
        value = state.draft.link,
        onValueChange = { onIntent(ReportContentIntent.LinkChanged(it)) },
        label = { Text(stringResource(R.string.report_link)) },
        supportingText = { Text(stringResource(R.string.report_link_hint)) },
        isError = !ReportMessageFormatter.isValidLink(state.draft.link),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        enabled = state.canEdit,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReportFormStatus(state: ReportContentUiState, onIntent: (ReportContentIntent) -> Unit) {
    val bringIntoView = remember { BringIntoViewRequester() }
    var placed by remember(state.error, state.formError) { mutableStateOf(false) }
    LaunchedEffect(placed, state.error, state.formError) {
        if (placed && (state.error != null || state.formError)) {
            bringIntoView.bringIntoView()
        }
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(bringIntoView).onGloballyPositioned { placed = true },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReportFormStatusContent(state, onIntent)
    }
}

@Composable
private fun ReportFormStatusContent(state: ReportContentUiState, onIntent: (ReportContentIntent) -> Unit) {
    if (state.isLoadingForm || (!state.draftReady && state.error == null)) {
        Text(stringResource(R.string.report_loading), style = MaterialTheme.typography.bodyMedium)
    }
    if (state.formError) {
        Text(stringResource(R.string.report_form_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { onIntent(ReportContentIntent.RetryForm) }) {
            Text(stringResource(R.string.report_retry))
        }
    }
    state.error?.let { error ->
        Text(
            stringResource(error.messageRes), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (error == ReportError.DRAFT_WRITE_FAILED && !state.draftReady) {
            TextButton(onClick = { onIntent(ReportContentIntent.RetryForm) }) {
                Text(stringResource(R.string.report_retry))
            }
        }
        if (error == ReportError.UNCERTAIN) {
            TextButton(onClick = { onIntent(ReportContentIntent.CheckMessages) }) {
                Text(stringResource(R.string.report_check_messages))
            }
        }
    }
    if (state.requiresLogin) {
        Button(onClick = { onIntent(ReportContentIntent.Login) }) { Text(stringResource(R.string.report_login)) }
    }
}

@Composable
private fun ReportReasons(state: ReportContentUiState, onIntent: (ReportContentIntent) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.report_reason), style = MaterialTheme.typography.titleMedium)
        ReportReason.entries.forEach { reason ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = state.draft.reason == reason, enabled = state.canEdit,
                    role = Role.RadioButton,
                    onClick = { onIntent(ReportContentIntent.ReasonChanged(reason)) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.draft.reason == reason, onClick = null, enabled = state.canEdit)
                Text(stringResource(reason.labelRes), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ReportSubmitButton(state: ReportContentUiState, onIntent: (ReportContentIntent) -> Unit) {
    ArmedSubmitButton(
        state = ArmedSubmitState(
            armed = state.armToken != null, enabled = state.canSubmit,
            showProgress = state.isSending, armToken = state.armToken,
        ),
        labels = ArmedSubmitLabels(stringResource(R.string.report_send), stringResource(R.string.report_confirm)),
        actions = ArmedSubmitActions(
            onSubmit = { onIntent(ReportContentIntent.Submit) },
            onConfirmSubmit = { onIntent(ReportContentIntent.Confirm(state.armToken)) },
            onDisarm = { onIntent(ReportContentIntent.DismissConfirmation) },
            onSubmitWithTimeout = { onIntent(ReportContentIntent.SubmitWithTimeout(it)) },
        ),
    )
}

private val ReportReason.labelRes: Int
    get() = when (this) {
        ReportReason.CHILD_SAFETY -> R.string.report_reason_child_safety
        ReportReason.ILLEGAL_CONTENT -> R.string.report_reason_illegal_content
        ReportReason.HARASSMENT -> R.string.report_reason_harassment
        ReportReason.APP_PROBLEM -> R.string.report_reason_app_problem
        ReportReason.OTHER -> R.string.report_reason_other
    }

private val ReportError.messageRes: Int
    get() = when (this) {
        ReportError.UNCERTAIN -> R.string.report_error_uncertain
        ReportError.DRAFT_WRITE_FAILED -> R.string.report_error_draft
        ReportError.SESSION_EXPIRED -> R.string.report_error_session
        ReportError.NETWORK -> R.string.report_error_network
        ReportError.INVALID_HASH -> R.string.report_error_hash
        ReportError.ANTI_FLOOD -> R.string.report_error_flood
        ReportError.UNKNOWN_RECIPIENT -> R.string.report_error_recipient
        ReportError.EMPTY_MESSAGE -> R.string.report_error_empty
        ReportError.REFUSED -> R.string.report_error_refused
    }
