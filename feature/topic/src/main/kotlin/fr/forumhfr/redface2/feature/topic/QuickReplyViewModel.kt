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
import fr.forumhfr.redface2.core.domain.write.ReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
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
    /**
     * #604 lot 2 — the quote cards, in citation order (reorderable). Transient like the
     * multi-quote basket : never persisted, the #405 row only ever carries the typed body.
     */
    val quotes: List<QuotedPostPreview> = emptyList(),
    val isSubmitting: Boolean = false,
    val submitError: QuickReplySubmitError? = null,
    /** #312 — « Confirmation avant publication » : the pending-confirmation dialog is showing. */
    val confirmVisible: Boolean = false,
) {
    /** With quote cards armed, an empty body is a valid submit (quotes-only reply). */
    val canSubmit: Boolean get() = (text.text.isNotBlank() || quotes.isNotEmpty()) && !isSubmitting
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
     * AFTER the save completed: the full editor restores the SAME `EditorDraftKey.reply` row
     * (auto-applied, #790), so the ordering is the whole transfer mechanism (cadrage Codex :
     * never a long text in a route arg, never a memory-only holder). [quotes] (#604 lot 3)
     * carries the armed cards as FULL previews through the :app handoff — the editor renders
     * the same cards (mockup P3) and defers the `[quotemsg]` materialisation to its own submit.
     */
    data class EscalateToFullEditor(val quotes: List<QuotedPostPreview>) : QuickReplyEffect
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
    private val quoteMaterializer: ReplyQuoteMaterializer,
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

    /** Owner snapshot + form warm-up ; joined by every draft read/write so they never race it. */
    private val initJob: Job = viewModelScope.launch {
        draftOwner = draftStore.currentOwner()
        prefetchForm()
    }

    init {
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().collect { enabled ->
                confirmBeforePosting = enabled
            }
        }
    }

    /**
     * Called at EACH sheet opening (not just the first): the VM outlives the sheet, so its
     * in-memory text can go stale whenever another surface touched the shared #405 row —
     * typically escalate → edit in the full-screen editor → back → reopen. The row is the
     * source of truth ; the field is unconditionally re-seeded from it (gate Codex PR #788).
     *
     * [initialQuotes] (#604 lot 3) — the cards this opening pre-arms, in citation order : one
     * preview for « Citer », the whole basket for « Citer N » under the full-screen threshold.
     * Adds are idempotent per numreponse, appended AFTER any cards the surviving VM already
     * holds (the composition in progress keeps its order).
     */
    fun onSheetOpened(initialQuotes: List<QuotedPostPreview> = emptyList()) {
        initialQuotes.forEach(::onQuoteAdded)
        viewModelScope.launch {
            initJob.join()
            val body = draftStore.load(draftOwner, draftKey)?.body.orEmpty()
            _state.update {
                it.copy(text = TextFieldValue(text = body, selection = TextRange(body.length)))
            }
        }
    }

    /** #604 lot 2 — arm a quote card ; idempotent per numreponse (re-citing a post is a no-op). */
    fun onQuoteAdded(preview: QuotedPostPreview) {
        _state.update { current ->
            if (current.quotes.any { it.numreponse == preview.numreponse }) {
                current
            } else {
                current.copy(quotes = current.quotes + preview)
            }
        }
    }

    fun onQuoteRemoved(numreponse: Int) {
        _state.update { current ->
            current.copy(quotes = current.quotes.filterNot { it.numreponse == numreponse })
        }
    }

    /** Move the card one slot up ([delta] = -1) or down (+1) ; out-of-range moves are no-ops. */
    fun onQuoteMoved(numreponse: Int, delta: Int) {
        _state.update { current ->
            val index = current.quotes.indexOfFirst { it.numreponse == numreponse }
            val target = index + delta
            if (index < 0 || target < 0 || target > current.quotes.lastIndex) return@update current
            val reordered = current.quotes.toMutableList().apply {
                add(target, removeAt(index))
            }
            current.copy(quotes = reordered)
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
            _effects.send(QuickReplyEffect.EscalateToFullEditor(_state.value.quotes))
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
        initJob.join()
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
                val quotes = _state.value.quotes
                if (quotes.isEmpty()) {
                    val form = loadedForm ?: replyRepository.fetchReplyForm(context).also { loadedForm = it }
                    replyRepository.submitReply(
                        context = context,
                        form = form,
                        bbcodeContent = _state.value.text.text,
                        options = form.options,
                    )
                } else {
                    // #604 lot 2 — materialise the [quotemsg] prefills fresh (never the cached
                    // plain form: the quote form carries the prefills AND the hash the submit
                    // rides). Card order = citation order ; the typed body follows the quotes.
                    // A network failure leaves body AND cards untouched (state only mutates on
                    // success), per the cadrage's partial-submit interdiction.
                    val quoteContext = context.copy(
                        quotedNumreponse = quotes.first().numreponse,
                        quoteRef = null,
                    )
                    val form = quoteMaterializer.fetchFormWithQuotes(
                        context = quoteContext,
                        extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                    )
                    val body = _state.value.text.text
                    replyRepository.submitReply(
                        context = quoteContext,
                        form = form,
                        // Quotes-only reply: no trailing blank lines after the last [quotemsg]
                        // (gate #798 — the exact BBCode is pinned by the VM tests).
                        bbcodeContent = if (body.isBlank()) {
                            form.initialContent.trimEnd()
                        } else {
                            form.initialContent.trimEnd() + "\n\n" + body
                        },
                        options = form.options,
                    )
                }
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
