package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSessionResources
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.write.QuotedNumreponses
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.toEditorError
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
    val quotes: List<QuoteSelection> = emptyList(),
    /** Projection of the request owned by EditorDraftSession, never a launch/Bundle authority. */
    val pendingQuotes: List<QuoteSelection> = emptyList(),
    val quoteFetchFailures: Int = 0,
    val quoteStorageUnavailable: Boolean = true,
    val restorableDraft: String? = null,
    val isDiscardingDraft: Boolean = false,
    val legacyQuoteRequestDropped: Boolean = false,
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val isSubmitting: Boolean = false,
    /**
     * #805 arbitrage — cards OFF : a `[quotemsg]` materialisation is in flight for this opening.
     * Typing stays enabled (the completion CONCATENATES onto the live field, réserve Codex) ;
     * submit and escalation wait for the insert.
     */
    val isPreparingQuotes: Boolean = false,
    val submitError: EditorError? = null,
    /** #312 — « Confirmation avant publication » : the pending-confirmation dialog is showing. */
    val confirmVisible: Boolean = false,
) {
    /** With quote cards armed, an empty body is a valid submit (quotes-only reply). */
    val canSubmit: Boolean
        get() = (text.text.isNotBlank() || quotes.isNotEmpty()) && !isSubmitting && !isPreparingQuotes &&
            !submission.blocksBodyMutation && pendingQuotes.isEmpty() && !quoteStorageUnavailable &&
            restorableDraft == null && !isDiscardingDraft

    val hasDormantQuote: Boolean
        get() = pendingQuotes.isNotEmpty() && submission is EditorSubmissionState.Uncertain

    val canIgnorePendingQuote: Boolean
        get() = pendingQuotes.isNotEmpty() && (quoteFetchFailures >= 2 || hasDormantQuote) &&
            !isPreparingQuotes && !submission.blocksBodyMutation && !isDiscardingDraft
}

/** One-shot events consumed by the sheet composable. */
sealed interface QuickReplyEffect {
    /**
     * HFR accepted the reply — refresh the topic like the full editor does (#200).
     * [quotedNumreponses] (#974) : the cited posts (appearance order ; inline `[quotemsg]` tags
     * and cards alike), empty for a plain reply — the topic engine lands on the highest one when
     * it is on the landing page, bottom otherwise.
     */
    data class SubmitSucceeded(
        val targetPage: Int?,
        val scrollTo: Int?,
        val quotedNumreponses: List<Int> = emptyList(),
    ) : QuickReplyEffect

    /**
     * The draft is persisted — the sheet can hand over to the full-screen editor. Emitted only
     * AFTER the save completed: the full editor restores the SAME `EditorDraftKey.reply` row
     * (auto-applied, #790), so the ordering is the whole transfer mechanism (cadrage Codex :
     * never a long text in a route arg, never a memory-only holder). [quotes] (#604 lot 3)
     * carries the armed cards as FULL previews through the :app handoff — the editor renders
     * the same cards (mockup P3) and defers the `[quotemsg]` materialisation to its own submit.
     */
    data class EscalateToFullEditor(val quotes: List<QuoteSelection>) : QuickReplyEffect
}

/**
 * Vague 4 (#604) lot 1 — the THIN ViewModel behind [QuickReplySheet]. Deliberately a fraction of
 * `PostEditorViewModel` (cadrage Codex : do not reuse it — its surface co-locates smileys, image
 * upload, previews and edit mode) : plain text in, [ReplyRepository] out, plus the #405 draft
 * contract shared with the full editor.
 *
 * Draft sharing IS the sheet↔full-screen transfer: both surfaces read and write the same
 * [EditorDraftKey.reply] row. One deliberate divergence from the full editor — the sheet
 * AUTO-applies a cached draft for an ordinary reply. When a new inline request meets an older
 * body, the sheet offers that body before fetching quotes: ignoring the body preserves the request.
 * Unresolved attempts always restore their body and keep their new request dormant.
 */
@HiltViewModel(assistedFactory = QuickReplyViewModel.Factory::class)
class QuickReplyViewModel @AssistedInject constructor(
    @Assisted private val request: QuickReplyRequest,
    private val savedStateHandle: SavedStateHandle,
    private val replyRepository: ReplyRepository,
    private val quoteMaterializer: TopicReplyQuoteMaterializer,
    private val sessionResources: EditorSessionResources,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickReplyUiState())
    val state: StateFlow<QuickReplyUiState> = _state.asStateFlow()

    private val _effects: Channel<QuickReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<QuickReplyEffect> = _effects.receiveAsFlow()

    /**
     * The VM is scoped to the topic nav entry, so [request] is the page of the FIRST sheet creation
     * only. The actual reply page is refreshed on every opening and snapshotted for each fetch /
     * submit so a second quick reply on another page posts the right HFR `page`.
     */
    private var currentPage: Int = request.page
    private val draftStore get() = sessionResources.drafts
    private val userPreferencesRepository get() = sessionResources.preferences

    /** Same lifecycle as the full editor : hash_check stays here, never in the Compose state. */
    private var loadedForm: ReplyForm? = null
    private var loadedFormPage: Int? = null
    private var formJob: Job? = null
    private var autosaveJob: Job? = null

    /**
     * #805 cards OFF — the opening-time materialisation. Cancelled at each (re)opening and at
     * dismiss : a fetch started for an abandoned composition can never inject its BBCode into
     * the next one (réserve Codex n°2).
     */
    private var materializeJob: Job? = null

    // #868-#870 (gate Sol) — one opening at a time : a re-open cancels the previous opening's
    // seed/arm work so two rapid openings can never finish out of order (last opening wins).
    private var openJob: Job? = null

    /** #405 — the SAME key the full-screen reply editor uses for this topic. */
    private val draftKey: String = EditorDraftKey.reply(request.cat, request.topicId)

    private var sessionReady = CompletableDeferred<Unit>()
    private var confirmationPreference: Boolean? = null
    private var submission = newSubmission()
    private var hasOpened = false
    private var sheetOpen = false

    /** ED-161/170: one binding per opening; old tasks keep their original session and owner. */
    private fun newSubmission(): EditorSubmissionBinding<QuickReplyEffect, EditorError> {
        val callbacks = EditorSubmissionCallbacks<QuickReplyEffect, EditorError>(
            state = { value ->
                _state.update {
                    it.copy(
                        submission = value,
                        isSubmitting = value.blocksBodyMutation,
                        confirmVisible = value is EditorSubmissionState.Armed,
                    )
                }
            },
            error = { value -> _state.update { it.copy(submitError = value) } },
            success = { value ->
                _state.update { it.copy(text = TextFieldValue(""), quotes = emptyList(), submitError = null) }
                _effects.trySend(value)
            },
        )
        return EditorSubmissionBinding(
            EditorSubmissionEnvironment(viewModelScope, sessionResources.clock, sessionResources.diagnostics),
            EditorDraftSession(draftStore, draftKey), callbacks,
            EditorSubmissionErrors(
                EditorErrors.uncertain, EditorErrors.draftWriteFailed,
                EditorErrors.sessionExpired, EditorErrors.uncertain,
                network = EditorErrors.network,
                preparationFailed = EditorErrors.invalidForm,
                quoteFetchFailed = EditorErrors.quoteFetchFailed(inline = false),
                draftSaveFailed = EditorErrors.draftSaveFailed,
            ),
        ).also { it.setPreference(confirmationPreference) }
    }

    init {
        observeAccount()
        val initial = submission
        val ready = sessionReady
        openJob = viewModelScope.launch {
            val entry = initial.restore(draftKey)
            if (submission !== initial) return@launch
            if (entry?.attempt != null) seedDraft(entry.draft.body)
            _state.update {
                it.copy(
                    quoteStorageUnavailable = !initial.isInitialized,
                    pendingQuotes = initial.drafts.pendingInlineQuoteRequest.toSelections(),
                )
            }
            ready.complete(Unit)
            if (initial.isInitialized) prefetchForm()
        }
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().catch { emit(true) }.collect { value ->
                confirmationPreference = value
                submission.setPreference(value)
            }
        }
    }

    /** ED-86/164/172: account changes revoke arms and unfinished hydration in this opening. */
    private fun observeAccount() {
        viewModelScope.launch {
            var observed = false
            var previous: String? = null
            sessionResources.auth.observeAuthState().collect { auth ->
                val owner = (auth as? AuthState.Authenticated)?.pseudo?.lowercase()
                if (observed && owner != previous) {
                    submission.accountChanged()
                    materializeJob?.cancel()
                    autosaveJob?.cancel()
                    openJob?.cancel()
                    sessionReady.cancel()
                    savedStateHandle[KEY_QUOTE_FAILURES] = 0
                    _state.update { it.copy(pendingQuotes = emptyList(), quoteStorageUnavailable = true) }
                }
                previous = owner
                observed = true
            }
        }
    }

    private fun seedDraft(body: String) {
        _state.update { it.copy(text = TextFieldValue(body, TextRange(body.length))) }
    }

    /** A restored mount retains the VM's current arm/deadline and buffered success. */
    fun onSheetAttached(currentPage: Int, initialQuotes: List<QuoteSelection>, restored: Boolean) {
        if (!restored || !hasOpened) onSheetOpened(currentPage, initialQuotes)
    }

    fun onSheetOpened(initialQuotes: List<QuoteSelection> = emptyList()) {
        onSheetOpened(currentPage = request.page, initialQuotes = initialQuotes)
    }

    /**
     * Reload the shared row at every opening. [initialQuotes] contains transient cards only;
     * inline gestures have already awaited their Room write in TopicViewModel.
     */
    fun onSheetOpened(currentPage: Int, initialQuotes: List<QuoteSelection> = emptyList()) {
        openSheet(currentPage, initialQuotes)
    }

    private fun openSheet(
        currentPage: Int, initialQuotes: List<QuoteSelection>, retainedText: TextFieldValue? = null,
    ) {
        if (submission.isExecuting) return
        hasOpened = true
        sheetOpen = true
        materializeJob?.cancel()
        autosaveJob?.cancel()
        openJob?.cancel()
        if (!sessionReady.isCompleted) submission.accountChanged()
        sessionReady.cancel()
        submission.disarm()
        updateCurrentPage(currentPage)
        // v20 launch coordinates are deliberately discarded, never decoded or imported.
        val legacy = savedStateHandle.remove<IntArray>("quickReply.pendingQuotes")
        savedStateHandle.remove<String>("quickReply.deliveredQuotes")
        savedStateHandle.remove<Boolean>("quickReply.pendingTransferred")
        _state.value = QuickReplyUiState(
            quotes = initialQuotes.distinctBy { it.numreponse },
            quoteFetchFailures = savedStateHandle[KEY_QUOTE_FAILURES] ?: 0,
            legacyQuoteRequestDropped = _state.value.legacyQuoteRequestDropped || legacy?.isNotEmpty() == true,
        )
        val opening = newSubmission()
        val ready = CompletableDeferred<Unit>()
        submission = opening
        sessionReady = ready
        openJob = viewModelScope.launch {
            val entry = opening.restore(draftKey)
            if (submission !== opening) return@launch
            val pending = opening.drafts.pendingInlineQuoteRequest
            val body = entry?.draft?.body.orEmpty()
            val offer = body.takeIf { it.isNotBlank() && pending != null && entry?.attempt == null }
            seedDraft(if (offer == null) body else "")
            retainedText?.takeIf { it.text.isNotBlank() }?.let { value -> _state.update { it.copy(text = value) } }
            _state.update { current ->
                val inlinePosts = pending?.quotes?.map { it.numreponse }.orEmpty().toSet()
                current.copy(
                    quoteStorageUnavailable = !opening.isInitialized,
                    restorableDraft = offer,
                    pendingQuotes = pending.toSelections(),
                    quotes = current.quotes.filterNot { it.numreponse in inlinePosts },
                )
            }
            ready.complete(Unit)
            if (!opening.isInitialized || !sheetOpen) return@launch
            prefetchForm()
            resumePendingInlineQuotes()
        }
    }

    private fun PendingInlineQuoteRequest?.toSelections(): List<QuoteSelection> = this?.quotes?.map {
        QuoteSelection(QuoteLocator(it.page, it.numreponse, it.ref), "", "", it.truncate)
    }.orEmpty()

    private fun missingInlineQuotes(): List<QuoteSelection> {
        val present = QuotedNumreponses.fromBbcode(_state.value.text.text).toSet()
        return _state.value.pendingQuotes.filterNot { it.numreponse in present }
    }

    private fun resumePendingInlineQuotes() {
        val current = _state.value
        if (!sheetOpen || !submission.isInitialized || submission.blocksMutation || current.isPreparingQuotes ||
            current.pendingQuotes.isEmpty() || current.hasDormantQuote || current.restorableDraft != null ||
            current.isDiscardingDraft
        ) return
        materializeInlineQuotes()
    }

    /** GETs may suspend while the user types; reconcile against the LIVE body before committing. */
    private fun materializeInlineQuotes() {
        submission.disarm()
        val opening = submission
        val baseContext = replyContext()
        val missing = missingInlineQuotes()
        _state.update { it.copy(isPreparingQuotes = true) }
        materializeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (missing.isNotEmpty()) {
                    val quoteContext = baseContext.copy(quotedNumreponse = missing.first().numreponse, quoteRef = null)
                    val form = quoteMaterializer.fetchFormWithQuotes(
                        context = quoteContext,
                        extraQuoteNumreponses = missing.drop(1).map { it.numreponse },
                        truncate = missing.any { it.truncate },
                    )
                    currentCoroutineContext().ensureActive()
                    check(form.initialContent.isNotBlank()) { "empty quote prefill" }
                    if (!canCompleteQuotePreparation(opening)) return@launch
                    rememberLoadedForm(form, quoteContext.page)
                    if (missingInlineQuotes() != missing) {
                        _state.update { it.copy(isPreparingQuotes = false) }
                        resumePendingInlineQuotes()
                        return@launch
                    }
                    val prefills = form.initialContent.trimEnd()
                    _state.update { current ->
                        val existing = current.text.text
                        val combined =
                            (if (existing.isBlank()) prefills else existing.trimEnd() + "\n\n" + prefills) + "\n"
                        current.copy(text = TextFieldValue(combined, TextRange(combined.length)))
                    }
                }
                commitInlineQuoteDelivery(opening)
            } catch (cancelled: CancellationException) {
                if (submission === opening) _state.update { it.copy(isPreparingQuotes = false) }
                throw cancelled
            } catch (_: SessionExpiredException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed(EditorErrors.sessionExpired)
            } catch (_: IOException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed()
            } catch (_: IllegalStateException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed()
            }
        }
        materializeJob?.start()
    }

    private suspend fun commitInlineQuoteDelivery(opening: EditorSubmissionBinding<QuickReplyEffect, EditorError>) {
        val pending = opening.drafts.pendingInlineQuoteRequest ?: return
        autosaveJob?.cancel()
        val result = opening.drafts.consumeInlineQuoteRequest(pending, EditorDraftStore.Draft(_state.value.text.text))
        if (!canCompleteQuotePreparation(opening)) return
        if (result is EditorDraftStore.StoreWriteResult.Written) {
            finishInlineQuoteDelivery()
            scheduleAutosave() // Preserve keystrokes made while the CAS returned.
        } else {
            quoteStorageFailed(result)
        }
    }

    private fun finishInlineQuoteDelivery() {
        savedStateHandle[KEY_QUOTE_FAILURES] = 0
        _state.update { current ->
            current.copy(
                pendingQuotes = emptyList(), isPreparingQuotes = false, quoteFetchFailures = 0,
                submitError = current.submitError.takeUnless {
                    it?.cause == EditorCause.QuoteFetchFailed || it?.cause == EditorCause.DraftSaveFailed
                },
            )
        }
    }

    private fun canCompleteQuotePreparation(opening: EditorSubmissionBinding<QuickReplyEffect, EditorError>): Boolean =
        submission === opening && !opening.blocksMutation && !opening.drafts.invalidated && sheetOpen

    private fun quotePreparationFailed(
        error: EditorError = EditorErrors.quoteFetchFailed(inline = true, quickReply = true),
    ) {
        val failures = _state.value.quoteFetchFailures + 1
        savedStateHandle[KEY_QUOTE_FAILURES] = failures
        _state.update { it.copy(isPreparingQuotes = false, quoteFetchFailures = failures, submitError = error) }
    }

    private fun quoteStorageFailed(result: EditorDraftStore.StoreWriteResult?) {
        _state.update {
            it.copy(
                isPreparingQuotes = false, isDiscardingDraft = false,
                quoteStorageUnavailable = it.quoteStorageUnavailable ||
                    result is EditorDraftStore.StoreWriteResult.Conflict,
                submitError = if (it.hasDormantQuote) EditorErrors.uncertain else EditorErrors.draftSaveFailed,
            )
        }
    }

    fun onQuoteReloadRequested() {
        if (_state.value.quoteStorageUnavailable) {
            if (submission.drafts.invalidated) openSheet(currentPage, emptyList()) else {
                openSheet(currentPage, _state.value.quotes, retainedText = _state.value.text)
            }
        } else {
            resumePendingInlineQuotes()
        }
    }

    fun onPendingQuoteIgnored() {
        if (!_state.value.canIgnorePendingQuote || submission.blocksMutation) return
        val opening = submission
        val pending = opening.drafts.pendingInlineQuoteRequest ?: return
        submission.disarm()
        autosaveJob?.cancel()
        _state.update { it.copy(isPreparingQuotes = true) }
        materializeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = opening.drafts.consumeInlineQuoteRequest(pending)
            if (!canCompleteQuotePreparation(opening)) return@launch
            if (result is EditorDraftStore.StoreWriteResult.Written) {
                finishInlineQuoteDelivery()
                scheduleAutosave()
            } else {
                quoteStorageFailed(result)
            }
        }
        materializeJob?.start()
    }

    fun onDormantQuoteResumed() {
        if (!_state.value.hasDormantQuote || _state.value.isPreparingQuotes || submission.blocksMutation) return
        submission.acknowledge { resumePendingInlineQuotes() }
    }

    fun onDraftRestoreRequested() {
        val offered = _state.value.restorableDraft ?: return
        if (_state.value.isDiscardingDraft || submission.blocksMutation) return
        submission.disarm()
        val existing = _state.value.text.text
        seedDraft(if (existing.isBlank() || existing == offered) offered else existing.trimEnd() + "\n\n" + offered)
        _state.update { it.copy(restorableDraft = null) }
        scheduleAutosave()
        resumePendingInlineQuotes()
    }

    fun onDraftDiscardRequested() {
        if (_state.value.restorableDraft == null || _state.value.isDiscardingDraft || submission.blocksMutation) return
        submission.disarm()
        autosaveJob?.cancel()
        val opening = submission
        _state.update { it.copy(isDiscardingDraft = true) }
        materializeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = opening.flush(EditorDraftStore.Draft(body = ""))
            if (!canCompleteQuotePreparation(opening)) return@launch
            if (result is EditorDraftStore.StoreWriteResult.Written) {
                _state.update { it.copy(restorableDraft = null, isDiscardingDraft = false) }
                scheduleAutosave()
                resumePendingInlineQuotes()
            } else {
                quoteStorageFailed(result)
            }
        }
        materializeJob?.start()
    }

    fun onLegacyQuoteNoticeDismissed() { _state.update { it.copy(legacyQuoteRequestDropped = false) } }

    /** #604 lot 2 — arm a quote card ; idempotent per numreponse (re-citing a post is a no-op). */
    fun onQuoteAdded(preview: QuoteSelection) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update { current ->
            if (current.quotes.any { it.numreponse == preview.numreponse }) {
                current
            } else {
                current.copy(quotes = current.quotes + preview)
            }
        }
    }

    fun onQuoteRemoved(numreponse: Int) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update { current ->
            current.copy(quotes = current.quotes.filterNot { it.numreponse == numreponse })
        }
    }

    /** Move the card one slot up ([delta] = -1) or down (+1) ; out-of-range moves are no-ops. */
    fun onQuoteMoved(numreponse: Int, delta: Int) {
        if (submission.blocksMutation) return
        submission.disarm()
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
        if (submission.blocksMutation || _state.value.isDiscardingDraft) return
        submission.disarm()
        _state.update {
            it.copy(
                text = value,
            )
        }
        scheduleAutosave()
    }

    /** ED-164/166/171: the existing dialog uses the same identity and monotonic bounds. */
    fun onSubmitClicked(timeoutMillis: Long = 4_000L) {
        if (!_state.value.canSubmit) return
        val permit = submission.request(timeoutMillis, _state.value.canSubmit)
        if (permit is SubmissionRequest.Accepted) submit(permit)
    }

    fun onSubmitConfirmed(token: ArmToken?) {
        if (!_state.value.canSubmit) return
        val permit = submission.confirm(token)
        if (permit is SubmissionRequest.Accepted) submit(permit)
    }

    fun onSubmitConfirmDismissed() = submission.disarm()

    /**
     * Persist the draft NOW (cancelling the pending debounce), then hand over to the full-screen
     * editor. The effect is emitted only after the save completed — see [QuickReplyEffect.EscalateToFullEditor].
     */
    fun onEscalateRequested() {
        // #805 cards OFF — inert while the [quotemsg] insert is in flight : escalating now would
        // hand over a row without the citation the user just asked for.
        if (submission.blocksClose || _state.value.isPreparingQuotes || _state.value.isDiscardingDraft) return
        submission.disarm()
        autosaveJob?.cancel()
        val session = submission
        val ready = sessionReady
        val body = bodyForHandoff()
        val quotes = _state.value.quotes
        viewModelScope.launch {
            ready.await()
            val result = session.flush(EditorDraftStore.Draft(body = body))
            if (result is EditorDraftStore.StoreWriteResult.Written) {
                if (submission !== session || session.drafts.invalidated) return@launch
                sheetOpen = false
                _effects.send(QuickReplyEffect.EscalateToFullEditor(quotes))
            }
        }
    }

    /**
     * Dirty close — flush the pending debounce so the last keystrokes reach the row (the sheet
     * preserves, it never asks) ; runs in [viewModelScope], which outlives the sheet (the VM is
     * scoped to the topic's nav entry).
     */
    fun onDismissed() {
        if (submission.blocksClose || _state.value.isDiscardingDraft) return
        sheetOpen = false
        submission.disarm()
        // Cancel the GET, retain its request: closing is not an explicit quote abandonment.
        materializeJob?.cancel()
        autosaveJob?.cancel()
        // #953 F2 — owner AND body captured NOW : the flush persists what the DISMISSED session
        // showed, under the owner of that session — regardless of what a subsequent re-opening
        // re-snapshots or masks before this write lands. If that re-opening seals this session
        // before its owner ever resolved, the await cancels the flush : a write with an unknown
        // owner never happens (gate Sol).
        val session = submission
        val ready = sessionReady
        val body = bodyForHandoff()
        viewModelScope.launch {
            ready.await()
            session.flush(EditorDraftStore.Draft(body = body))
        }
    }

    private fun bodyForHandoff(): String {
        val current = _state.value
        val offered = current.restorableDraft ?: return current.text.text
        return if (current.text.text.isBlank() || current.text.text == offered) offered else {
            current.text.text.trimEnd() + "\n\n" + offered
        }
    }

    private fun scheduleAutosave() {
        if (submission.blocksMutation || _state.value.restorableDraft != null || _state.value.isDiscardingDraft) return
        autosaveJob?.cancel()
        // #953 F2 — same capture-at-scheduling rule as [onDismissed] : session and body belong
        // to the keystroke that scheduled this debounce, whatever happens before it fires.
        val session = submission
        val ready = sessionReady
        val body = _state.value.text.text
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            ready.await()
            session.flush(EditorDraftStore.Draft(body = body))
        }
    }

    /** Warm the hash_check early so the first submit doesn't pay the GET ; errors stay silent. */
    private fun prefetchForm() {
        val context = replyContext()
        if (loadedFormFor(context.page) != null || formJob?.isActive == true) return
        formJob = viewModelScope.launch {
            val form = runCatching { replyRepository.fetchReplyForm(context) }.getOrNull()
            if (form != null && currentPage == context.page) {
                rememberLoadedForm(form, context.page)
            }
        }
    }

    /** ED-88/169/170: body/cards/page are snapshotted before the durable attempt. */
    private fun submit(permit: SubmissionRequest.Accepted) {
        autosaveJob?.cancel()
        val snapshot = _state.value
        val baseContext = replyContext()
        val quoted = QuotedNumreponses.of(snapshot.text.text, snapshot.quotes)
        submission.launch(permit, EditorDraftStore.Draft(body = snapshot.text.text)) {
            deliverReply(snapshot, baseContext, quoted)
        }
    }

    private suspend fun deliverReply(
        snapshot: QuickReplyUiState,
        baseContext: ReplyContext,
        quoted: List<Int>,
    ): EditorDelivery<QuickReplyEffect, EditorError> {
        val quotes = snapshot.quotes
        val context = if (quotes.isEmpty()) baseContext else baseContext.copy(
            quotedNumreponse = quotes.first().numreponse, quoteRef = null,
        )
        val form = prepareForm(context, quotes)
        val body = snapshot.text.text
        val content = when {
            quotes.isEmpty() -> body
            body.isBlank() -> form.initialContent.trimEnd()
            else -> form.initialContent.trimEnd() + "\n\n" + body
        }
        return when (val result = replyRepository.submitReply(context, form, content, form.options)) {
            is ReplySubmitResult.Success -> EditorDelivery(
                effect = QuickReplyEffect.SubmitSucceeded(result.targetPage, result.numreponse, quoted),
            )
            is ReplySubmitResult.Failure -> {
                val error = if (result.reason == ReplyFailureReason.Unknown) {
                    EditorErrors.uncertain
                } else {
                    result.reason.toEditorError()
                }
                EditorDelivery(
                    outcome = if (result.reason == ReplyFailureReason.Unknown) {
                        EditorSendOutcome.UNCERTAIN
                    } else {
                        EditorSendOutcome.CERTAIN_FAILURE
                    },
                    error = error,
                    afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                        {
                            loadedForm = null
                            prefetchForm()
                        }
                    } else { null },
                )
            }
        }
    }

    private suspend fun prepareForm(context: ReplyContext, quotes: List<QuoteSelection>): ReplyForm = try {
        if (quotes.isEmpty()) {
            loadedFormFor(context.page) ?: replyRepository.fetchReplyForm(context).also {
                rememberLoadedForm(it, context.page)
            }
        } else {
            quoteMaterializer.fetchFormWithQuotes(
                context = context, extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                truncate = quotes.any { it.truncate },
            )
        }
    } catch (_: SessionExpiredException) {
        throw EditorPreparationException(EditorPreparationException.Reason.SESSION_EXPIRED)
    } catch (_: IOException) {
        val reason = if (quotes.isEmpty()) {
            EditorPreparationException.Reason.NETWORK
        } else {
            EditorPreparationException.Reason.QUOTE_FETCH
        }
        throw EditorPreparationException(reason)
    } catch (_: IllegalStateException) {
        throw EditorPreparationException(if (quotes.isEmpty()) {
            EditorPreparationException.Reason.INVALID_FORM
        } else {
            EditorPreparationException.Reason.QUOTE_FETCH
        })
    }

    private fun updateCurrentPage(page: Int) {
        if (page == currentPage) return
        currentPage = page
        loadedForm = null
        loadedFormPage = null
        formJob?.cancel()
        formJob = null
    }

    private fun replyContext(): ReplyContext = ReplyContext(
        cat = request.cat,
        subcat = request.subcat,
        topicId = request.topicId,
        page = currentPage,
    )

    private fun loadedFormFor(page: Int): ReplyForm? =
        loadedForm.takeIf { loadedFormPage == page }

    private fun rememberLoadedForm(form: ReplyForm, page: Int) {
        loadedForm = form
        loadedFormPage = page
    }

    @AssistedFactory
    interface Factory {
        fun create(request: QuickReplyRequest): QuickReplyViewModel
    }

    private companion object {
        /** Same debounce as the other #405 consumers. */
        const val AUTOSAVE_DEBOUNCE_MS = 750L

        /** UI retry count only; request existence and delivery belong exclusively to Room. */
        const val KEY_QUOTE_FAILURES = "quickReply.quoteFetchFailures"
    }
}
