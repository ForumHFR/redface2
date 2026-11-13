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
import fr.forumhfr.redface2.core.domain.editor.EditorBodyProvenance
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
import kotlinx.coroutines.flow.filter
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
    val isLoadingDraft: Boolean = false,
    val unreadableQuoteRequest: Boolean = false,
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
    /** The hidden sheet must retain neither field focus nor input while its close is settling. */
    val isClosing: Boolean = false,
    /**
     * ED-10 on the sheet (#1450) — the awaited last save of a close was refused : the sheet is
     * hidden but still mounted, and « Brouillon non enregistré » asks before the text is abandoned.
     */
    val unsavedCloseVisible: Boolean = false,
) {
    /** With quote cards armed, an empty body is a valid submit (quotes-only reply). */
    val canSubmit: Boolean
        get() = (text.text.isNotBlank() || quotes.isNotEmpty()) && !isSubmitting && !isPreparingQuotes &&
            !submission.blocksBodyMutation && pendingQuotes.isEmpty() && !quoteStorageUnavailable &&
            restorableDraft == null && !isDiscardingDraft && !isClosing

    val hasDormantQuote: Boolean
        get() = pendingQuotes.isNotEmpty() && submission is EditorSubmissionState.Uncertain

    @Suppress("ComplexCondition") // Explicit recovery, or an eligible readable request, plus busy-state guards.
    val canIgnorePendingQuote: Boolean
        get() = (unreadableQuoteRequest ||
            pendingQuotes.isNotEmpty() && (quoteFetchFailures >= 2 || hasDormantQuote)) &&
            !isLoadingDraft &&
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
     * (auto-applied, #790). Escalation waits for a decision on any restore offer.
     * Ordering is the transfer mechanism (cadrage Codex :
     * never a long text in a route arg, never a memory-only holder). [quotes] (#604 lot 3)
     * carries the armed cards as FULL previews through the :app handoff — the editor renders
     * the same cards (mockup P3) and defers the `[quotemsg]` materialisation to its own submit.
     */
    data class EscalateToFullEditor(val quotes: List<QuoteSelection>) : QuickReplyEffect

    /**
     * ED-10 on the sheet (#1450) — the close is settled : the last save is durable (or there was
     * nothing to write), or the user chose « Quitter sans enregistrer ». Only now may the topic
     * surface unmount the sheet ; until then it stays composed, hidden, so its collector lives.
     */
    data class CloseCommitted(val openingId: Long) : QuickReplyEffect
}

/** A sealed C/Q delivery belongs to one sheet opening, including its restored mounts. */
data class QuickReplyQuotesDelivered(val openingId: Long)

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
 * Under an undecided offer, typing autosaves C in the main row; D stays only in restorableDraft.
 * Process recreation reoffers the durable body C. Escalation requires the offer's decision first.
 * Unresolved attempts always restore their body and keep their new request dormant.
 */
@Suppress("LargeClass") // Sheet MVI plus the #405 draft and durable quote contracts, like PostEditorViewModel.
@HiltViewModel(assistedFactory = QuickReplyViewModel.Factory::class)
class QuickReplyViewModel @AssistedInject constructor(
    @Assisted private val request: QuickReplyRequest,
    private val savedStateHandle: SavedStateHandle,
    private val replyRepository: ReplyRepository,
    private val quoteMaterializer: TopicReplyQuoteMaterializer,
    private val sessionResources: EditorSessionResources,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickReplyUiState(isLoadingDraft = true))
    val state: StateFlow<QuickReplyUiState> = _state.asStateFlow()

    private val _effects: Channel<QuickReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<QuickReplyEffect> = _effects.receiveAsFlow().filter {
        it !is QuickReplyEffect.CloseCommitted || it.openingId == openingId
    }

    /**
     * ED-88 (#1616) — one signal per SEALED C/Q transaction : the quoted body is in the row and
     * its request is erased, together. The topic surface empties the multi-quote basket on it. Its
     * own conflated channel, not an effect : a delivery is not a navigation outcome, and nothing
     * here may reorder or delay the close and submit effects.
     */
    private val _quotesDelivered = Channel<QuickReplyQuotesDelivered>(capacity = Channel.CONFLATED)
    val quotesDelivered: Flow<QuickReplyQuotesDelivered> = _quotesDelivered.receiveAsFlow().filter {
        it.openingId == openingId
    }

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
    private var openingId = 0L
    private var sheetOpen = false
    private var closeStarted = false

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
            clearError = { error ->
                _state.update { if (it.submitError == error) it.copy(submitError = null) else it }
            },
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
                    isLoadingDraft = false,
                    unreadableQuoteRequest = initial.drafts.hasUnreadableInlineQuoteRequest,
                    submitError = if (initial.drafts.hasUnreadableInlineQuoteRequest) {
                        EditorErrors.unreadableQuoteRequest
                    } else { it.submitError },
                    pendingQuotes = initial.drafts.pendingInlineQuoteRequest.toSelections(),
                )
            }
            ready.complete(Unit)
            val waitsForDecision = _state.value.hasDormantQuote ||
                (_state.value.pendingQuotes.isNotEmpty() && entry?.draft?.body?.isNotBlank() == true)
            if (initial.isInitialized && !waitsForDecision) prefetchForm()
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
    fun onSheetAttached(
        currentPage: Int, initialQuotes: List<QuoteSelection>, restored: Boolean,
        legacyQuoteRequestDropped: Boolean = false,
    ) {
        if (!restored || !hasOpened) {
            openSheet(currentPage, initialQuotes, restored = restored)
            if (legacyQuoteRequestDropped) _state.update { it.copy(legacyQuoteRequestDropped = true) }
        }
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

    @Suppress("CyclomaticComplexMethod") // One opening sequence: reset, Room read, restore offer, then state seed.
    private fun openSheet(
        currentPage: Int, initialQuotes: List<QuoteSelection>, retainedText: TextFieldValue? = null,
        restored: Boolean = false,
    ) {
        if (submission.isExecuting) return
        hasOpened = true
        sheetOpen = true
        closeStarted = false
        // Both flows reject buffered signals from older openings. A restored mount keeps this id.
        openingId++
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
            isLoadingDraft = true,
            quotes = initialQuotes.distinctBy { it.numreponse },
            quoteFetchFailures = savedStateHandle[KEY_QUOTE_FAILURES] ?: 0,
            legacyQuoteRequestDropped = _state.value.legacyQuoteRequestDropped || legacy?.isNotEmpty() == true,
        )
        quoteSeal = null
        restoredOfferAwaitingWrite = null
        mergedDraftOffer = null
        resolvingDraftOffer = false
        val opening = newSubmission()
        val ready = CompletableDeferred<Unit>()
        submission = opening
        sessionReady = ready
        openJob = viewModelScope.launch {
            val entry = opening.restore(draftKey)
            val pending = opening.drafts.pendingInlineQuoteRequest
            val quoteOnly = pending == null && entry?.attempt == null &&
                entry?.draft?.bodyProvenance?.isQuoteOnly(entry.draft.body) == true
            val discardQuoteOnly = quoteOnly && retainedText == null && !restored
            if (discardQuoteOnly && submission === opening) {
                opening.resolveDraftWrite(opening.drafts.discardQuoteOnly())
            }
            if (submission !== opening) return@launch
            val body = if (discardQuoteOnly) "" else entry?.draft?.body.orEmpty()
            val offer = body.takeIf { it.isNotBlank() && pending != null && entry?.attempt == null }
                ?: opening.drafts.offers.firstOrNull { it.draft.body != body }?.draft?.body
            // The sheet auto-applies ordinary C. It must not preserve that same session as D
            // on its next autosave/send; only a body awaiting a quote decision is an offer.
            if (entry != null && offer != body) opening.drafts.resumeContent()
            seedDraft(if (offer == body) "" else body)
            retainedText?.takeIf { it.text.isNotBlank() }?.let { value -> _state.update { it.copy(text = value) } }
            _state.update { current ->
                val inlinePosts = pending?.quotes?.map { it.numreponse }.orEmpty().toSet()
                current.copy(
                    quoteStorageUnavailable = !opening.isInitialized,
                    isLoadingDraft = false,
                    unreadableQuoteRequest = opening.drafts.hasUnreadableInlineQuoteRequest,
                    submitError = if (opening.drafts.hasUnreadableInlineQuoteRequest) {
                        EditorErrors.unreadableQuoteRequest
                    } else { current.submitError },
                    restorableDraft = offer,
                    pendingQuotes = pending.toSelections(),
                    quotes = current.quotes.filterNot { it.numreponse in inlinePosts },
                )
            }
            ready.complete(Unit)
            if (!opening.isInitialized || !sheetOpen) return@launch
            if (!_state.value.hasDormantQuote && offer == null) prefetchForm()
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

    @Suppress("ComplexCondition") // One guard per state that must not resume the inline quote fetch.
    private fun resumePendingInlineQuotes() {
        val current = _state.value
        if (!sheetOpen || !submission.isInitialized || submission.blocksMutation || current.isPreparingQuotes ||
            current.pendingQuotes.isEmpty() || current.hasDormantQuote || current.restorableDraft != null ||
            current.isDiscardingDraft || resolvingDraftOffer
        ) return
        materializeInlineQuotes()
    }

    /**
     * A C/Q transaction this opening attempted and has not sealed yet. [prefill] is the quote
     * block the GET inserted, null when the expected tags were already in the field. Any resolution
     * of Q retires it : a sealed delivery, a successful Ignore, a new opening.
     */
    private class QuoteSeal(val prefill: String?)
    private var quoteSeal: QuoteSeal? = null
    private var restoredOfferAwaitingWrite: String? = null
    private var mergedDraftOffer: String? = null
    private var resolvingDraftOffer = false

    private fun materializeInlineQuotes() {
        submission.disarm()
        val opening = submission
        val baseContext = replyContext()
        // GETs may suspend while the user types; reconcile against the LIVE body before committing.
        val missing = missingInlineQuotes()
        launchQuoteWork(opening) {
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
                    if (!canCompleteQuotePreparation(opening)) return@launchQuoteWork
                    rememberLoadedForm(form, quoteContext.page)
                    if (missingInlineQuotes() != missing) {
                        _state.update { it.copy(isPreparingQuotes = false) }
                        resumePendingInlineQuotes()
                        return@launchQuoteWork
                    }
                    val prefills = form.initialContent.trimEnd()
                    quoteSeal = QuoteSeal(prefills)
                    _state.update { current ->
                        val existing = current.text.text
                        val combined =
                            (if (existing.isBlank()) prefills else existing.trimEnd() + "\n\n" + prefills) + "\n"
                        current.copy(text = TextFieldValue(combined, TextRange(combined.length)))
                    }
                }
                // Tags already in the field seal the same transaction as an inserted prefill.
                quoteSeal = quoteSeal ?: QuoteSeal(prefill = null)
                commitInlineQuoteDelivery(opening)
            } catch (cancelled: CancellationException) {
                throw cancelled // An IllegalStateException on the JVM : never a failed preparation.
            } catch (_: SessionExpiredException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed(EditorErrors.sessionExpired)
            } catch (_: IOException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed()
            } catch (_: IllegalStateException) {
                if (canCompleteQuotePreparation(opening)) quotePreparationFailed()
            }
        }
    }

    /**
     * Every C/Q task (insertion, reload, Ignore) runs here. Whatever ends it — an outcome, an
     * early exit on the hidden sheet, a cancellation — releases the busy state of ITS session,
     * unless a later task of that session already took over.
     */
    private fun launchQuoteWork(
        opening: EditorSubmissionBinding<QuickReplyEffect, EditorError>, work: suspend () -> Unit,
    ) {
        _state.update { it.copy(isPreparingQuotes = true) }
        materializeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val task = currentCoroutineContext()[Job]
            try {
                work()
            } finally {
                if (submission === opening && materializeJob === task) {
                    _state.update { it.copy(isPreparingQuotes = false) }
                }
            }
        }
        materializeJob?.start()
    }

    private suspend fun commitInlineQuoteDelivery(opening: EditorSubmissionBinding<QuickReplyEffect, EditorError>) {
        val pending = opening.drafts.pendingInlineQuoteRequest ?: return
        val deliveryOpeningId = openingId
        autosaveJob?.cancel()
        val result = opening.drafts.consumeInlineQuoteRequest(pending, EditorDraftStore.Draft(_state.value.text.text),
            deliveredBody = quoteSeal?.prefill)
        // A cancelled GET may already be committing its non-cancellable C/Q transaction. Its
        // delivery belongs to THIS opening even when the sheet is now hidden awaiting that write.
        if (submission !== opening || opening.drafts.invalidated) return
        if (result is EditorDraftStore.StoreWriteResult.Written) {
            opening.resolveDraftWrite(result)
            finishInlineQuoteDelivery()
            quoteSeal = null
            _quotesDelivered.trySend(QuickReplyQuotesDelivered(deliveryOpeningId))
            if (sheetOpen) scheduleAutosave() // A close owns its final snapshot instead.
        } else {
            // A refused non-cancellable write must release busy state even on the hidden sheet.
            quoteStorageFailed(result)
        }
    }

    private fun finishInlineQuoteDelivery() {
        savedStateHandle[KEY_QUOTE_FAILURES] = 0
        _state.update { current ->
            current.copy(
                pendingQuotes = emptyList(), isPreparingQuotes = false, quoteFetchFailures = 0,
                submitError = current.submitError.takeUnless {
                    it?.cause == EditorCause.QuoteFetchFailed
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
        submission.resolveDraftWrite(result)
        _state.update {
            it.copy(
                isPreparingQuotes = false, isDiscardingDraft = false,
                quoteStorageUnavailable = it.quoteStorageUnavailable ||
                    result is EditorDraftStore.StoreWriteResult.Conflict,
                submitError = if (it.unreadableQuoteRequest) EditorErrors.unreadableQuoteRequest else it.submitError,
            )
        }
    }

    fun onQuoteReloadRequested() {
        if (_state.value.isLoadingDraft) return
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
        val pending = opening.drafts.pendingInlineQuoteRequest
        val unreadable = _state.value.unreadableQuoteRequest
        submission.disarm()
        autosaveJob?.cancel()
        launchQuoteWork(opening) {
            val result = if (unreadable) opening.drafts.discardUnreadableInlineQuoteRequest() else {
                opening.drafts.consumeInlineQuoteRequest(checkNotNull(pending))
            }
            settleIgnoredQuote(opening, result, unreadable)
        }
    }

    /**
     * The removal is not cancellable : like a delivery, its outcome settles THIS session even
     * when the sheet is hidden awaiting it. Only the reload of an unreadable row needs the sheet.
     */
    private fun settleIgnoredQuote(
        opening: EditorSubmissionBinding<QuickReplyEffect, EditorError>,
        result: EditorDraftStore.StoreWriteResult, unreadable: Boolean,
    ) {
        if (submission !== opening || opening.drafts.invalidated) return
        if (result !is EditorDraftStore.StoreWriteResult.Written) {
            quoteStorageFailed(result)
            return
        }
        opening.resolveDraftWrite(result)
        when {
            !unreadable -> {
                // Q is resolved : nothing is left to seal, a close saves C like any other body.
                quoteSeal = null
                finishInlineQuoteDelivery()
                scheduleAutosave() // Inert once a close started : it owns its final snapshot.
            }
            sheetOpen -> openSheet(currentPage, _state.value.quotes, retainedText = _state.value.text)
        }
    }

    fun onDormantQuoteResumed() {
        if (!_state.value.hasDormantQuote || _state.value.isPreparingQuotes || submission.blocksMutation) return
        submission.acknowledge { resumePendingInlineQuotes() }
    }

    fun onDraftRestoreRequested() {
        val offered = _state.value.restorableDraft ?: return
        if (_state.value.isDiscardingDraft || resolvingDraftOffer || submission.blocksMutation) return
        submission.disarm()
        autosaveJob?.cancel()
        val existing = _state.value.text.text
        val needsMerge = mergedDraftOffer != offered
        mergedDraftOffer = offered
        restoredOfferAwaitingWrite = offered
        if (needsMerge) {
            seedDraft(if (existing.isBlank() || existing == offered) offered else existing.trimEnd() + "\n\n" + offered)
        }
        _state.update { it.copy(restorableDraft = null) }
        resolveDraftOffer(offered, restoring = true)
    }

    private fun resolveDraftOffer(body: String, restoring: Boolean = false) {
        val opening = submission
        resolvingDraftOffer = true
        if (!restoring) _state.update { it.copy(isDiscardingDraft = true) }
        materializeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                opening.flush(null)
                val draft = if (restoring) draftSnapshot() else EditorDraftStore.Draft(body = _state.value.text.text)
                val offer = opening.drafts.offered(body)
                val result = if (offer != null && !restoring && draft != null) {
                    opening.drafts.resolveOffer(offer, draft)
                } else opening.flush(draft)
                if (submission === opening) resolvingDraftOffer = false
                if (!canCompleteQuotePreparation(opening)) return@launch
                opening.resolveDraftWrite(result)
                if (result is EditorDraftStore.StoreWriteResult.Written) {
                    restoredOfferAwaitingWrite = null
                    mergedDraftOffer = null
                    _state.update { it.copy(restorableDraft = null, isDiscardingDraft = false) }
                    scheduleAutosave()
                    resumePendingInlineQuotes()
                } else {
                    _state.update { it.copy(restorableDraft = body) }
                    quoteStorageFailed(result)
                }
            } finally {
                if (submission === opening) {
                    restoredOfferAwaitingWrite = null
                    resolvingDraftOffer = false
                    scheduleAutosave()
                }
            }
        }
        materializeJob?.start()
    }

    fun onDraftDiscardRequested() {
        val hasDiscardableDraft = _state.value.restorableDraft != null && !_state.value.isDiscardingDraft
        if (!hasDiscardableDraft || resolvingDraftOffer || submission.blocksMutation) return
        submission.disarm()
        autosaveJob?.cancel()
        resolveDraftOffer(checkNotNull(_state.value.restorableDraft))
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
        if (closeStarted || submission.blocksMutation || _state.value.isDiscardingDraft) return
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
        val busy = _state.value.isPreparingQuotes || _state.value.isDiscardingDraft
        if (closeStarted || submission.blocksClose || busy) return
        // D is memory-only under an offer: the user must decide before handing over the row.
        if (_state.value.quoteStorageUnavailable || _state.value.restorableDraft != null) return
        submission.disarm()
        autosaveJob?.cancel()
        val session = submission
        val ready = sessionReady
        val body = _state.value.text.text
        val quotes = _state.value.quotes
        viewModelScope.launch {
            ready.await()
            val result = session.flush(EditorDraftStore.Draft(body = body))
            if (result is EditorDraftStore.StoreWriteResult.Written) {
                if (submission !== session || session.drafts.invalidated) return@launch
                sheetOpen = false
                closeStarted = true
                _effects.send(QuickReplyEffect.EscalateToFullEditor(quotes))
            }
        }
    }

    /**
     * Close request — the sheet has slid away but is still mounted. Flush the pending debounce so
     * the last keystrokes reach the row, AWAIT that write, then settle (ED-10, #1450) :
     * [QuickReplyEffect.CloseCommitted] when nothing is abandoned, the unsaved-close question
     * otherwise. Never silent, never a blind unmount. Runs in [viewModelScope], which outlives
     * the sheet (the VM is scoped to the topic's nav entry).
     */
    fun onDismissed() {
        if (closeStarted || submission.blocksClose || _state.value.isDiscardingDraft) return
        closeStarted = true
        sheetOpen = false
        submission.disarm()
        _state.update { it.copy(isClosing = true) }
        // Cancel the GET, retain its request: closing is not an explicit quote abandonment.
        materializeJob?.cancel()
        autosaveJob?.cancel()
        // #953 F2 — owner AND body captured NOW : the flush persists what the DISMISSED session
        // showed, under the owner of that session — regardless of what a subsequent re-opening
        // re-snapshots or masks before this write lands. If that re-opening seals this session
        // before its owner ever resolved, the await is cancelled and nothing is written : a write
        // with an unknown owner never happens (gate Sol).
        val closing = ClosingSheet(submission, sessionReady, draftSnapshot(), shownDraft(), materializeJob)
        val closingId = openingId
        viewModelScope.launch {
            val saved = saveForClose(closing)
            // A re-opening superseded this close : its outcome belongs to no mounted sheet.
            if (submission !== closing.session) return@launch
            if (saved) _effects.send(QuickReplyEffect.CloseCommitted(closingId)) else onFinalSaveRefused()
        }
    }

    /** What one close request froze : its session, that session's owner gate, and its field. */
    private class ClosingSheet(
        val session: EditorSubmissionBinding<QuickReplyEffect, EditorError>,
        val ready: CompletableDeferred<Unit>,
        val draft: EditorDraftStore.Draft?,
        val shown: EditorDraftStore.Draft,
        val quoteWork: Job?,
    )

    private fun shownDraft() = EditorDraftStore.Draft(body = _state.value.text.text)

    /**
     * True when closing abandons nothing (same rule as the full-screen editors,
     * [EditorSubmissionBinding.flushForClose]). A sealed session — account change, or a
     * re-opening before the owner resolved — can never write again : nothing to save, the close
     * goes through. Only this coroutine's own cancellation propagates.
     */
    private suspend fun saveForClose(closing: ClosingSheet): Boolean {
        // A cancelled quote task may still be adopting a non-cancellable C/Q write. Await its
        // delivery signal as well as its write before CloseCommitted can retire the collector.
        closing.quoteWork?.join()
        try {
            closing.ready.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            return true
        }
        return try {
            // Like the full editors, seal a delivered quote before the final save of C.
            if (awaitsQuoteSeal(closing.session)) commitInlineQuoteDelivery(closing.session)
            !awaitsQuoteSeal(closing.session) && closing.session.flushForClose(
                // The hidden sheet takes no input ; a superseding opening must not be read as typing.
                live = { if (submission === closing.session) shownDraft() else closing.shown },
                snapshot = { closing.draft },
            )
        } catch (_: IOException) {
            false
        }
    }

    /**
     * An attempted C/Q transaction is still owed : Q is pending in THIS session and the field
     * holds every quote it asks for. Anything else closes through the ordinary final save.
     */
    private fun awaitsQuoteSeal(session: EditorSubmissionBinding<QuickReplyEffect, EditorError>): Boolean =
        submission === session && quoteSeal != null && session.drafts.pendingInlineQuoteRequest != null &&
            missingInlineQuotes().isEmpty()

    /**
     * UX switch point (#1450, pending XaTriX) — what a refused final save does. Current choice
     * (option A) : keep the hidden sheet mounted and ask with the editors' dialog. Option B
     * (immediate close + Topic snackbar) would commit the close here and emit a dedicated effect.
     */
    private fun onFinalSaveRefused() {
        _state.update { it.copy(unsavedCloseVisible = true) }
    }

    /**
     * The informed choice of « Brouillon non enregistré ». Leaving commits the close exactly once,
     * without a write. Staying re-opens THIS session with its field intact — never a reload from
     * the row, which does not hold the text — and a later dismiss retries the save.
     */
    fun onUnsavedCloseDecided(leave: Boolean) {
        if (!_state.value.unsavedCloseVisible) return
        _state.update { it.copy(unsavedCloseVisible = false, isClosing = leave) }
        if (leave) {
            _effects.trySend(QuickReplyEffect.CloseCommitted(openingId))
        } else {
            closeStarted = false
            sheetOpen = true
            // The close cancelled a quote GET in flight ; its durable request is still pending.
            resumePendingInlineQuotes()
        }
    }

    private fun scheduleAutosave() {
        if (closeStarted || submission.blocksMutation || _state.value.isDiscardingDraft) return
        autosaveJob?.cancel()
        // #953 F2 — same capture-at-scheduling rule as [onDismissed] : session and body belong
        // to the keystroke that scheduled this debounce, whatever happens before it fires.
        val session = submission
        val ready = sessionReady
        val draft = draftSnapshot()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            ready.await()
            session.flush(draft)
        }
    }

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        // ED-121: C stays in the shared row; a first replacement protects the offered version transactionally.
        if (snapshot.text.text.isBlank() && snapshot.restorableDraft != null) return null
        val body = snapshot.text.text
        val restoring = restoredOfferAwaitingWrite ?: mergedDraftOffer
        val offer = restoring?.let(submission.drafts::offered)
        val provenance = if (restoring != null && body.startsWith(restoring)) {
            (submission.drafts.bodyProvenance ?: EditorBodyProvenance())
                .copy(draftEnd = restoring.length, quotesEnd = restoring.length)
        } else null
        return EditorDraftStore.Draft(body = body, bodyProvenance = provenance, offerToResolve = offer)
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
