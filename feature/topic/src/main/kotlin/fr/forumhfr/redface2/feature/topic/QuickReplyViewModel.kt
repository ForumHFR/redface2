package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Vague 4 (#604) lot 1 — everything the quick-reply sheet needs to talk to HFR for one topic. */
data class QuickReplyRequest(
    val cat: Int,
    val subcat: Int,
    val topicId: Int,
    val page: Int,
)

/** UI state of the quick-reply sheet. [text] is the whole contract — no toolbar, no preview. */
data class QuickReplyUiState(
    val text: TextFieldValue = TextFieldValue(""),
    val isSubmitting: Boolean = false,
    val submitError: QuickReplySubmitError? = null,
    /** #312 — « Confirmation avant publication » : the pending-confirmation dialog is showing. */
    val confirmVisible: Boolean = false,
) {
    val canSubmit: Boolean get() = text.text.isNotBlank() && !isSubmitting
}

/** Typed submit failures, the same split as the full editor's `SubmitError`. */
sealed interface QuickReplySubmitError {
    data class Hfr(val reason: ReplyFailureReason) : QuickReplySubmitError
    data object Network : QuickReplySubmitError
    data object SessionExpired : QuickReplySubmitError
}

/** One-shot events consumed by the sheet composable. */
sealed interface QuickReplyEffect {
    /** HFR accepted the reply — refresh the topic like the full editor does (#200). */
    data class SubmitSucceeded(val targetPage: Int?, val scrollTo: Int?) : QuickReplyEffect

    /**
     * The draft is persisted — the sheet can hand over to the full-screen editor. Emitted only
     * AFTER the save completed: the full editor restores the SAME `EditorDraftKey.reply` row,
     * so the ordering is the whole transfer mechanism (cadrage Codex vague 4 : never a long
     * text in a route arg, never a memory-only holder).
     */
    data object EscalateToFullEditor : QuickReplyEffect
}

/**
 * Vague 4 (#604) lot 1 — the THIN ViewModel behind [QuickReplySheet]. Deliberately a fraction of
 * `PostEditorViewModel` (cadrage Codex : do not reuse it — its surface co-locates smileys, image
 * upload, previews and edit mode) : plain text in, [ReplyRepository] out, plus the #405 draft
 * contract shared with the full editor.
 *
 * Draft sharing IS the sheet↔full-screen transfer: both surfaces read and write the same
 * [EditorDraftKey.reply] row. One deliberate divergence from the full editor — the sheet
 * AUTO-applies a cached draft into the field instead of surfacing a restore banner: the sheet is
 * the « resume quickly » surface, and its dirty-close contract (autosave, never a silent loss)
 * means the field content and the row are the same thing.
 */
@HiltViewModel(assistedFactory = QuickReplyViewModel.Factory::class)
class QuickReplyViewModel @AssistedInject constructor(
    @Assisted private val request: QuickReplyRequest,
    private val replyRepository: ReplyRepository,
    private val draftStore: EditorDraftStore,
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickReplyUiState())
    val state: StateFlow<QuickReplyUiState> = _state.asStateFlow()

    private val _effects: Channel<QuickReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<QuickReplyEffect> = _effects.receiveAsFlow()

    private val context = ReplyContext(
        cat = request.cat,
        subcat = request.subcat,
        topicId = request.topicId,
        page = request.page,
    )

    /** Same lifecycle as the full editor : hash_check stays here, never in the Compose state. */
    private var loadedForm: ReplyForm? = null
    private var formJob: Job? = null
    private var submitJob: Job? = null
    private var autosaveJob: Job? = null

    /** #405 — the SAME key the full-screen reply editor uses for this topic. */
    private val draftKey: String = EditorDraftKey.reply(request.cat, request.topicId)

    /** #405 — account snapshotted at open so a mid-edit switch can't cross-write drafts. */
    private var draftOwner: String? = null

    /** #312 — mirror of the persisted « Confirmation avant publication » preference. */
    private var confirmBeforePosting: Boolean = false

    init {
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().collect { enabled ->
                confirmBeforePosting = enabled
            }
        }
        viewModelScope.launch {
            draftOwner = draftStore.currentOwner()
            val body = draftStore.load(draftOwner, draftKey)?.body
            if (!body.isNullOrBlank() && _state.value.text.text.isBlank()) {
                _state.update {
                    it.copy(text = TextFieldValue(text = body, selection = TextRange(body.length)))
                }
            }
            prefetchForm()
        }
    }

    fun onTextChanged(value: TextFieldValue) {
        _state.update { it.copy(text = value, submitError = null) }
        scheduleAutosave()
    }

    /** #312 gate first ; the actual POST happens in [submit]. */
    fun onSubmitClicked() {
        if (!_state.value.canSubmit) return
        if (confirmBeforePosting) {
            _state.update { it.copy(confirmVisible = true) }
        } else {
            submit()
        }
    }

    fun onSubmitConfirmed() {
        _state.update { it.copy(confirmVisible = false) }
        submit()
    }

    fun onSubmitConfirmDismissed() {
        _state.update { it.copy(confirmVisible = false) }
    }

    /**
     * Persist the draft NOW (cancelling the pending debounce), then hand over to the full-screen
     * editor. The effect is emitted only after the save completed — see [QuickReplyEffect.EscalateToFullEditor].
     */
    fun onEscalateRequested() {
        autosaveJob?.cancel()
        viewModelScope.launch {
            saveDraftNow()
            _effects.send(QuickReplyEffect.EscalateToFullEditor)
        }
    }

    /**
     * Dirty close — flush the pending debounce so the last keystrokes reach the row (the sheet
     * preserves, it never asks) ; runs in [viewModelScope], which outlives the sheet (the VM is
     * scoped to the topic's nav entry).
     */
    fun onDismissed() {
        autosaveJob?.cancel()
        viewModelScope.launch { saveDraftNow() }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveDraftNow()
        }
    }

    private suspend fun saveDraftNow() {
        val body = _state.value.text.text
        if (body.isBlank()) {
            draftStore.delete(draftOwner, draftKey)
        } else {
            draftStore.save(draftOwner, draftKey, EditorDraftStore.Draft(body = body))
        }
    }

    /** Warm the hash_check early so the first submit doesn't pay the GET ; errors stay silent. */
    private fun prefetchForm() {
        if (loadedForm != null || formJob?.isActive == true) return
        formJob = viewModelScope.launch {
            loadedForm = runCatching { replyRepository.fetchReplyForm(context) }.getOrNull()
        }
    }

    private fun submit() {
        if (submitJob?.isActive == true) return
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                val form = loadedForm ?: replyRepository.fetchReplyForm(context).also { loadedForm = it }
                replyRepository.submitReply(
                    context = context,
                    form = form,
                    bbcodeContent = _state.value.text.text,
                    options = form.options,
                )
            }
            outcome.fold(
                onSuccess = { result -> handleSubmitOutcome(result) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private suspend fun handleSubmitOutcome(result: ReplySubmitResult) {
        when (result) {
            is ReplySubmitResult.Success -> {
                // Same contract as the full editor: the draft dies with the successful POST,
                // awaited so a process death cannot resurrect an already-published reply.
                autosaveJob?.cancel()
                draftStore.delete(draftOwner, draftKey)
                _state.update { QuickReplyUiState() }
                _effects.send(
                    QuickReplyEffect.SubmitSucceeded(
                        targetPage = result.targetPage,
                        scrollTo = result.numreponse,
                    ),
                )
            }
            is ReplySubmitResult.Failure -> {
                // InvalidHashCheck = the cached form expired mid-edit ; silently refetch so the
                // user's next tap works (mirrors PostEditorViewModel.handleSubmitOutcome).
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    prefetchForm()
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = QuickReplySubmitError.Hfr(result.reason))
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
            is SessionExpiredException -> QuickReplySubmitError.SessionExpired
            is IOException -> QuickReplySubmitError.Network
            else -> QuickReplySubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: QuickReplyRequest): QuickReplyViewModel
    }

    private companion object {
        /** Same debounce as the other #405 consumers. */
        const val AUTOSAVE_DEBOUNCE_MS = 750L
    }
}
