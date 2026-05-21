package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2D #148 — ViewModel for the topic-level form. Currently scoped to
 * [TopicFormMode.EditFirstPost] ; [TopicFormMode.New] (Phase 2E #149) still
 * lands on the placeholder. Architecture mirrors [PostEditorViewModel] :
 *
 * 1. On init, fetch the topic form via [TopicFormRepository.fetchEditFirstPostForm]
 *    using `(cat, subcat, topicId, page, numreponse)` from the request.
 * 2. Hydrate `subject`, `draft`, the three per-post options, and the parsed
 *    subcategory selection ONCE — subsequent silent refetches (e.g. after
 *    `InvalidHashCheck`) must never overwrite user edits.
 * 3. On [TopicFormIntent.SubmitClicked], POST via
 *    [TopicFormRepository.submitEditFirstPost] with the user's final values.
 * 4. On success, emit [TopicFormEffect.SubmitSucceeded] carrying `targetPage`
 *    and `scrollTo = numreponse` so the navigation host can refresh the
 *    topic and scroll to the edited FP.
 */
@HiltViewModel(assistedFactory = TopicFormViewModel.Factory::class)
class TopicFormViewModel @AssistedInject constructor(
    @Assisted private val request: TopicFormRequest,
    private val previewParser: BbcodePreviewParser,
    private val topicFormRepository: TopicFormRepository,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    private val _state: MutableStateFlow<TopicFormState> = MutableStateFlow(
        TopicFormState(
            mode = request.mode,
            cat = request.cat,
            subcat = request.subcat,
            topicId = request.topicId,
            page = request.page,
            numreponse = request.numreponse,
        ),
    )
    val state: StateFlow<TopicFormState> = _state.asStateFlow()

    private val _effects: Channel<TopicFormEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicFormEffect> = _effects.receiveAsFlow()

    private var loadedForm: TopicForm? = null
    private var submitJob: Job? = null

    init {
        if (request.mode == TopicFormMode.EditFirstPost) {
            loadEditFirstPostFormIfPossible()
        }
    }

    fun submit(intent: TopicFormIntent) {
        when (intent) {
            is TopicFormIntent.SubjectChanged -> onSubjectChanged(intent.value)
            is TopicFormIntent.ContentChanged -> onContentChanged(intent.value)
            is TopicFormIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            TopicFormIntent.TogglePreview -> onTogglePreview()
            TopicFormIntent.SubmitClicked -> onSubmitClicked()
            TopicFormIntent.ErrorDismissed -> _state.update { it.copy(submitError = null) }
            is TopicFormIntent.SubcatSelected ->
                _state.update { it.copy(selectedSubcat = intent.id) }
            is TopicFormIntent.ToggleSignature ->
                _state.update { it.copy(signatureEnabled = intent.enabled) }
            is TopicFormIntent.ToggleSmileyDisabled ->
                _state.update { it.copy(smileyDisabled = intent.disabled) }
            is TopicFormIntent.ToggleEmailNotification ->
                _state.update { it.copy(emailNotificationEnabled = intent.enabled) }
        }
    }

    private fun onSubjectChanged(value: TextFieldValue) {
        _state.update { current ->
            current.copy(
                subject = value,
                submitError = if (value.text != current.subject.text) null else current.submitError,
            )
        }
    }

    private fun onContentChanged(value: TextFieldValue) {
        _state.update { current ->
            val refreshed = current.withDraft(value)
            if (refreshed.isPreviewVisible) {
                refreshed.copy(preview = previewParser.parsePreview(refreshed.draft.text))
            } else {
                refreshed
            }
        }
    }

    private fun onToolbarActionClicked(action: BbcodeAction) {
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = applyBbcodeAction(
                action = action,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
    }

    private fun onTogglePreview() {
        _state.update { current ->
            val nextVisible = !current.isPreviewVisible
            current.copy(
                isPreviewVisible = nextVisible,
                preview = if (nextVisible) previewParser.parsePreview(current.draft.text) else current.preview,
            )
        }
    }

    private fun loadEditFirstPostFormIfPossible() {
        val context = buildEditFirstPostContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchEditFirstPostForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    val snapshot = _state.value
                    val shouldHydrate = snapshot.shouldHydrateFrom(form)
                    // Pre-compute the preview off the state lambda — same
                    // dispatcher rationale as `PostEditorViewModel`.
                    val nextPreview = if (shouldHydrate && snapshot.isPreviewVisible) {
                        previewParser.parsePreview(form.initialContent)
                    } else {
                        snapshot.preview
                    }
                    _state.update { current -> current.withFormHydration(form, nextPreview) }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun handleFetchFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isLoadingForm = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "FP fetch bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isLoadingForm = false, submitError = mapped) }
    }

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitClicked() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val context = buildEditFirstPostContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val form = loadedForm ?: run {
            loadEditFirstPostFormIfPossible()
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = SubmitError.Hfr(ReplyFailureReason.LoginRequired)) }
            return
        }
        if (submitJob?.isActive == true) return

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        val selectedSubcat = snapshot.selectedSubcat ?: error("canSubmit lied about selectedSubcat")
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                topicFormRepository.submitEditFirstPost(
                    context = context,
                    form = form,
                    subject = snapshot.subject.text,
                    bbcodeContent = snapshot.draft.text,
                    selectedSubcat = selectedSubcat,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = { result -> handleSubmitOutcome(snapshot.numreponse, result) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun handleSubmitOutcome(
        numreponse: Int?,
        result: ReplySubmitResult,
    ) {
        when (result) {
            is ReplySubmitResult.Success -> {
                _effects.trySend(
                    TopicFormEffect.SubmitSucceeded(
                        targetPage = result.targetPage,
                        // FP refresh URL anchors `#t{numreponse}` ; we surface
                        // it so the navigation host can scroll to the edited
                        // FP after refresh.
                        scrollTo = numreponse,
                    ),
                )
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadEditFirstPostFormIfPossible()
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = SubmitError.Hfr(result.reason))
                }
            }
        }
    }

    private fun handleSubmitFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isSubmitting = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "FP submit bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    @Suppress("ReturnCount", "ComplexCondition") // Each guard returns null with a distinct reason ; the
    // composite check enforces the four invariants of `EditFirstPostContext.init` before construction.
    private fun buildEditFirstPostContext(): EditFirstPostContext? {
        val snapshot = state.value
        val cat = snapshot.cat ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        val page = snapshot.page ?: return null
        val numreponse = snapshot.numreponse ?: return null
        if (subcat <= 0 || topicId <= 0 || numreponse <= 0 || page != 1) return null
        return EditFirstPostContext(
            cat = cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            numreponse = numreponse,
        )
    }

    private fun TopicFormState.shouldHydrateFrom(form: TopicForm): Boolean =
        !formHydratedFromServer &&
            subject.text.isBlank() &&
            draft.text.isBlank() &&
            (form.subject.isNotBlank() || form.initialContent.isNotBlank())

    private fun TopicFormState.withFormHydration(
        form: TopicForm,
        nextPreview: PostContent,
    ): TopicFormState {
        val shouldHydrate = shouldHydrateFrom(form)
        val nextSubject = if (shouldHydrate) {
            TextFieldValue(
                text = form.subject,
                selection = TextRange(form.subject.length),
            )
        } else {
            subject
        }
        val nextDraft = if (shouldHydrate) {
            TextFieldValue(
                text = form.initialContent,
                selection = TextRange(form.initialContent.length),
            )
        } else {
            draft
        }
        val hydrateOptions = !optionsHydratedFromForm
        return copy(
            isLoadingForm = false,
            subject = nextSubject,
            draft = nextDraft,
            preview = if (shouldHydrate && isPreviewVisible) nextPreview else preview,
            formHydratedFromServer = formHydratedFromServer || shouldHydrate,
            selectedSubcat = if (hydrateOptions) form.selectedSubcat else selectedSubcat,
            subcategoryChoices = form.subcategoryChoices,
            pollPresent = form.poll.present,
            pollEditable = form.poll.editableInThisVersion,
            signatureEnabled = if (hydrateOptions) form.options.signatureEnabled else signatureEnabled,
            smileyDisabled = if (hydrateOptions) form.options.smileyDisabled else smileyDisabled,
            emailNotificationEnabled = if (hydrateOptions) {
                form.options.emailNotificationEnabled
            } else {
                emailNotificationEnabled
            },
            optionsHydratedFromForm = true,
            submitError = if (form.isAnonymous) {
                SubmitError.Hfr(ReplyFailureReason.LoginRequired)
            } else {
                submitError
            },
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(request: TopicFormRequest): TopicFormViewModel
    }

    private companion object {
        private const val LOG_TAG_VM = "TopicFormVM"
    }
}
