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
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
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
 * ViewModel backing the post-level editor. Owns the BBCode draft, the parsed
 * preview AST, the preview-visibility toggle, and the Phase 2C (#145) reply
 * submission lifecycle :
 *
 * 1. On init, when the request is a reply with a known `(page, subcat, topicId)`,
 *    fetches the HFR reply form (`message.php`) to grab the per-session
 *    `hash_check` and the hidden contract fields.
 * 2. On [PostEditorIntent.SubmitClicked], POSTs `bddpost.php` via the repository
 *    and emits a one-shot [PostEditorEffect.SubmitSucceeded] on success.
 *
 * Anti-double-submit is enforced via [PostEditorState.isSubmitting] + a single
 * [submitJob] reference that ignores re-entry while in flight. Errors classified
 * by the repository are surfaced via [SubmitError]; the draft is preserved.
 */
@HiltViewModel(assistedFactory = PostEditorViewModel.Factory::class)
class PostEditorViewModel @AssistedInject constructor(
    @Assisted private val request: PostEditorRequest,
    private val previewParser: BbcodePreviewParser,
    private val replyRepository: ReplyRepository,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    private val _state: MutableStateFlow<PostEditorState> = MutableStateFlow(
        PostEditorState(
            mode = request.mode,
            cat = request.cat,
            topicId = request.topicId,
            numreponse = request.numreponse,
            page = request.page,
            subcat = request.subcat,
            quotedNumreponse = request.quotedNumreponse,
            quoteRef = request.quoteRef,
        ),
    )
    val state: StateFlow<PostEditorState> = _state.asStateFlow()

    private val _effects: Channel<PostEditorEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PostEditorEffect> = _effects.receiveAsFlow()

    /**
     * Cached form pulled lazily on [PostEditorMode.Reply]. Keeping it on the
     * ViewModel rather than the [PostEditorState] avoids leaking `hash_check`
     * through Compose snapshot tooling / state restoration.
     */
    private var loadedForm: ReplyForm? = null
    private var submitJob: Job? = null

    init {
        if (request.mode == PostEditorMode.Reply) {
            loadReplyFormIfPossible()
        }
    }

    fun submit(intent: PostEditorIntent) {
        when (intent) {
            is PostEditorIntent.ContentChanged -> onContentChanged(intent.value)
            is PostEditorIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            PostEditorIntent.TogglePreview -> onTogglePreview()
            PostEditorIntent.SubmitClicked -> onSubmitClicked()
            PostEditorIntent.ErrorDismissed -> _state.update { it.copy(submitError = null) }
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

    private fun loadReplyFormIfPossible() {
        val context = buildReplyContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { replyRepository.fetchReplyForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    _state.update { current ->
                        // Hydrate the draft from HFR's quote prefill the first time the form
                        // lands and only when the user has not typed anything yet. Two
                        // important guards :
                        //   1. `draftHydratedFromForm` flips to true once we've ever
                        //      prefilled, so an InvalidHashCheck silent refetch later
                        //      cannot overwrite the user's edits with the same prefill.
                        //   2. Even on the *first* load, if the user already typed before
                        //      the network came back, `draft.text.isNotBlank()` wins.
                        // For a simple reply, `form.initialContent` is empty and we leave
                        // the draft untouched.
                        val shouldHydrate = !current.draftHydratedFromForm &&
                            current.draft.text.isBlank() &&
                            form.initialContent.isNotBlank()
                        val nextDraft = if (shouldHydrate) {
                            TextFieldValue(
                                text = form.initialContent,
                                // Place caret at the end so the user can type their reply
                                // right after the cited block — matches HFR's web behavior.
                                selection = TextRange(form.initialContent.length),
                            )
                        } else {
                            current.draft
                        }
                        // If the user had toggled the preview on before the form
                        // landed (rare), we deliberately leave the preview AST
                        // stale rather than parsing on whichever dispatcher
                        // `viewModelScope.launch {}` resolved to (default
                        // `Dispatchers.Main.immediate`). The next `ContentChanged`
                        // or `TogglePreview` intent will refresh it via the
                        // existing pipeline — see #146 review round 1 note re.
                        // « preview parse non-IO » follow-up for Phase 2D.
                        current.copy(
                            isLoadingForm = false,
                            draft = nextDraft,
                            draftHydratedFromForm = current.draftHydratedFromForm || shouldHydrate,
                            submitError = if (form.isAnonymous) {
                                SubmitError.Hfr(ReplyFailureReason.LoginRequired)
                            } else {
                                current.submitError
                            },
                        )
                    }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun handleFetchFailure(error: Throwable) {
        if (error is CancellationException) {
            // Symmetry with `handleSubmitFailure` : reset the in-flight flag before the throw so
            // a parent ViewModel that survives the cancellation (or any state observer reading
            // the snapshot before death) does not see `isLoadingForm = true` forever.
            _state.update { it.copy(isLoadingForm = false) }
            throw error
        }
        // Map known transport-level failures to a typed SubmitError ; classify the
        // rest as Unknown rather than letting the exception bubble up and crash the
        // process. The UI surfaces the same "unexpected response" message either way.
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "fetch bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isLoadingForm = false, submitError = mapped) }
    }

    @Suppress("ReturnCount") // guard clauses are the natural shape of the dispatcher
    private fun onSubmitClicked() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val context = buildReplyContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val form = loadedForm ?: run {
            // Form not loaded yet — fetch it then bail out; user re-clicks once ready.
            loadReplyFormIfPossible()
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = SubmitError.Hfr(ReplyFailureReason.LoginRequired)) }
            return
        }
        if (submitJob?.isActive == true) return

        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                replyRepository.submitReply(
                    context = context,
                    form = form,
                    bbcodeContent = snapshot.draft.text,
                )
            }
            outcome.fold(
                onSuccess = ::handleSubmitOutcome,
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun handleSubmitOutcome(result: ReplySubmitResult) {
        when (result) {
            is ReplySubmitResult.Success -> {
                _effects.trySend(PostEditorEffect.SubmitSucceeded(targetPage = result.targetPage))
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is ReplySubmitResult.Failure -> {
                // InvalidHashCheck typically means the cached form has expired ;
                // refetch silently and let the user re-submit.
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadReplyFormIfPossible()
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
            "submit bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    @Suppress("ReturnCount") // Each guard returns null with a distinct reason
    private fun buildReplyContext(): ReplyContext? {
        val snapshot = state.value
        val page = snapshot.page ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        // Mirror the `Topic.hasSubcat` / `ReplyContext.init` rule : reject both the
        // sentinel (-1) and the moderator-space wire shape (0).
        if (subcat <= 0) return null
        return ReplyContext(
            cat = snapshot.cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            // Phase 2C (#146) : both fields are null for a simple reply ; both
            // non-null for a quote launched from `TopicScreen.onQuote`. The model
            // tolerates a quote with a null `quoteRef` for forward compat (HFR
            // could drop `ref` someday), but we keep them aligned in practice.
            quotedNumreponse = snapshot.quotedNumreponse,
            quoteRef = snapshot.quoteRef,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PostEditorRequest): PostEditorViewModel
    }

    private companion object {
        // Distinct from the repository's "ReplyRepository" tag so the diagnostics
        // panel makes it obvious which layer recorded an entry.
        private const val LOG_TAG_VM = "PostEditorVM"
    }
}
