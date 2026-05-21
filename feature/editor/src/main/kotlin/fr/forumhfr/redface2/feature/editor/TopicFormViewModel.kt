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
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
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
 * ViewModel for the topic-level form. [TopicFormMode.EditFirstPost] edits an
 * existing first post (Phase 2D #148) and [TopicFormMode.New] creates a topic
 * (Phase 2E #149). Architecture mirrors [PostEditorViewModel] :
 *
 * 1. On init, fetch the topic form via [TopicFormRepository] using the
 *    mode-specific request shape.
 * 2. Hydrate `subject`, `draft`, the three per-post options, and the parsed
 *    subcategory selection ONCE — subsequent silent refetches (e.g. after
 *    `InvalidHashCheck`) must never overwrite user edits.
 * 3. On [TopicFormIntent.SubmitClicked], POST via the matching repository method
 *    with the user's final values.
 * 4. On success, emit [TopicFormEffect.SubmitSucceeded] for edit FP or
 *    [TopicFormEffect.NewTopicCreated] for create-topic navigation.
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
            // New-topic has nothing useful to hydrate into `subject` / `draft`
            // from the server (the user is writing from scratch). Lock both
            // hydration flags to `true` from the start so a silent
            // `InvalidHashCheck` refetch can never clobber what the user
            // already typed. Edit FP keeps the default `false` until the
            // fetched form actually carries content.
            subjectHydratedFromServer = request.mode == TopicFormMode.New,
            draftHydratedFromServer = request.mode == TopicFormMode.New,
        ),
    )
    val state: StateFlow<TopicFormState> = _state.asStateFlow()

    private val _effects: Channel<TopicFormEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicFormEffect> = _effects.receiveAsFlow()

    private var loadedForm: TopicForm? = null
    private var submitJob: Job? = null

    init {
        when (request.mode) {
            TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
            TopicFormMode.New -> loadNewTopicFormIfPossible()
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
                    // Pre-compute the preview off the state lambda — same
                    // dispatcher rationale as `PostEditorViewModel`. Only matters
                    // if the draft is going to be hydrated AND the preview pane
                    // is open ; otherwise we keep the current preview.
                    val nextPreview = if (snapshot.shouldHydrateDraftFrom(form) && snapshot.isPreviewVisible) {
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

    private fun onSubmitClicked() {
        when (_state.value.mode) {
            TopicFormMode.EditFirstPost -> onSubmitEditFirstPostClicked()
            TopicFormMode.New -> onSubmitNewTopicClicked()
        }
    }

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitEditFirstPostClicked() {
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

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitNewTopicClicked() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val context = buildNewTopicContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val form = loadedForm ?: run {
            loadNewTopicFormIfPossible()
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
                topicFormRepository.submitNewTopic(
                    context = context,
                    form = form,
                    subject = snapshot.subject.text,
                    bbcodeContent = snapshot.draft.text,
                    selectedSubcat = selectedSubcat,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = { result -> handleNewTopicOutcome(context, selectedSubcat, result) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun loadNewTopicFormIfPossible() {
        val context = buildNewTopicContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchNewTopicForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    // The new-topic flow never hydrates subject/draft from the
                    // server (init already locked both flags to `true`), so we
                    // don't need to recompute the preview here. We do still
                    // need to land options + subcategory choices in state.
                    _state.update { current -> current.withFormHydration(form, current.preview) }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun handleNewTopicOutcome(
        context: NewTopicContext,
        selectedSubcat: Int,
        result: NewTopicSubmitResult,
    ) {
        when (result) {
            is NewTopicSubmitResult.Success -> {
                _effects.trySend(
                    TopicFormEffect.NewTopicCreated(
                        cat = context.cat,
                        subcat = selectedSubcat,
                        newTopicId = result.newTopicId,
                        newNumreponse = result.newNumreponse,
                    ),
                )
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is NewTopicSubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadNewTopicFormIfPossible()
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = SubmitError.Hfr(result.reason))
                }
            }
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

    /**
     * Builds the [NewTopicContext] when the routing state has enough data to
     * fetch the create-topic form. `entrySubcat` is the chip the user came
     * from (nullable on the « Toutes » view) ; the final subcat lands at
     * submit time via `selectedSubcat` from the dropdown, never through this
     * context.
     */
    private fun buildNewTopicContext(): NewTopicContext? {
        val snapshot = _state.value
        val cat = snapshot.cat?.takeIf { it > 0 } ?: return null
        val entrySubcat = snapshot.subcat?.takeIf { it > 0 }
        return NewTopicContext(cat = cat, entrySubcat = entrySubcat)
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

    private fun TopicFormState.shouldHydrateSubjectFrom(form: TopicForm): Boolean =
        !subjectHydratedFromServer && subject.text.isBlank() && form.subject.isNotBlank()

    private fun TopicFormState.shouldHydrateDraftFrom(form: TopicForm): Boolean =
        !draftHydratedFromServer && draft.text.isBlank() && form.initialContent.isNotBlank()

    private fun TopicFormState.withFormHydration(
        form: TopicForm,
        nextPreview: PostContent,
    ): TopicFormState {
        // Hydrate each field independently : a slow fetch that lands after the
        // user started typing in only one of the two fields must still hydrate
        // the other one without clobbering the user's edit.
        val hydrateSubject = shouldHydrateSubjectFrom(form)
        val hydrateDraft = shouldHydrateDraftFrom(form)
        val nextSubject = if (hydrateSubject) {
            TextFieldValue(
                text = form.subject,
                selection = TextRange(form.subject.length),
            )
        } else {
            subject
        }
        val nextDraft = if (hydrateDraft) {
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
            preview = if (hydrateDraft && isPreviewVisible) nextPreview else preview,
            subjectHydratedFromServer = subjectHydratedFromServer || hydrateSubject,
            draftHydratedFromServer = draftHydratedFromServer || hydrateDraft,
            // The form's `selectedSubcat` is `Int?` post-#149 :
            //  - Edit FP : non-null by `parseEditFirstPost` contract, kept as-is.
            //  - New : HFR serves no pre-selection, so `form.selectedSubcat` is
            //    null. We fall back to `subcat` (the entry chip from the
            //    request), letting the user override via the dropdown later.
            selectedSubcat = if (hydrateOptions) form.selectedSubcat ?: subcat else selectedSubcat,
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
            // Propagate the parsed anonymous flag so `canSubmit` can refuse
            // the POST locally (the wire would refuse too, but we don't want
            // to leak attempt artefacts in the diagnostics buffer either).
            isAnonymous = form.isAnonymous,
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
