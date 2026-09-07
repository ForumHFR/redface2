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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.QuotedNumreponses
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val isSubmitting: Boolean = false,
    /**
     * #805 arbitrage — cards OFF : a `[quotemsg]` materialisation is in flight for this opening.
     * Typing stays enabled (the completion CONCATENATES onto the live field, réserve Codex) ;
     * submit and escalation wait for the insert.
     */
    val isPreparingQuotes: Boolean = false,
    val submitError: QuickReplySubmitError? = null,
    /** #312 — « Confirmation avant publication » : the pending-confirmation dialog is showing. */
    val confirmVisible: Boolean = false,
) {
    /** With quote cards armed, an empty body is a valid submit (quotes-only reply). */
    val canSubmit: Boolean
        get() = (text.text.isNotBlank() || quotes.isNotEmpty()) && !isSubmitting && !isPreparingQuotes
}

/** Typed submit failures, the same split as the full editor's `SubmitError`. */
sealed interface QuickReplySubmitError {
    data class Hfr(val reason: ReplyFailureReason) : QuickReplySubmitError
    data object Network : QuickReplySubmitError
    data object SessionExpired : QuickReplySubmitError

    /**
     * #805 cards OFF — the opening-time `[quotemsg]` fetch failed : the field is untouched,
     * nothing is lost, re-tapping « Citer » retries. Session expiry still maps to
     * [SessionExpired] (reconnect is the actionable fix).
     */
    data object QuoteFetchFailed : QuickReplySubmitError
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
 * AUTO-applies a cached draft into the field instead of surfacing a restore banner: the sheet is
 * the « resume quickly » surface, and its dirty-close contract (autosave, never a silent loss)
 * means the field content and the row are the same thing.
 */
@HiltViewModel(assistedFactory = QuickReplyViewModel.Factory::class)
class QuickReplyViewModel @AssistedInject constructor(
    @Assisted private val request: QuickReplyRequest,
    private val savedStateHandle: SavedStateHandle,
    private val replyRepository: ReplyRepository,
    private val quoteMaterializer: TopicReplyQuoteMaterializer,
    private val draftStore: EditorDraftStore,
    private val userPreferencesRepository: UserPreferencesRepository,
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

    /** Same lifecycle as the full editor : hash_check stays here, never in the Compose state. */
    private var loadedForm: ReplyForm? = null
    private var loadedFormPage: Int? = null
    private var formJob: Job? = null
    private var submitJob: Job? = null
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

    /**
     * #405 / #953 F2 — the account the CURRENT opening session's draft reads/writes ride.
     * Re-snapshotted at EVERY sheet opening : the VM is nav-entry-scoped and outlives the sheet,
     * so a single construction-time capture would replay account A's owner after a switch to
     * account B (B would read A's private row, and B's submit would sweep it). One
     * [CompletableDeferred] per session — draft tasks capture their session synchronously at
     * scheduling and await the owner inside, so a deferred write (autosave debounce, dismiss
     * flush, submit delete) from a PREVIOUS session can never ride the owner re-snapshotted by
     * the next opening. A re-opening additionally SEALS (cancels) the previous session if its
     * owner never resolved : a late [EditorDraftStore.currentOwner] must not hand the NEXT
     * account's owner to the dead session's tasks — they no-op instead (gate Sol).
     */
    private var sessionOwner: CompletableDeferred<String?> = CompletableDeferred()

    /** #312 — mirror of the persisted « Confirmation avant publication » preference. */
    private var confirmBeforePosting: Boolean = false

    init {
        // Initial owner snapshot + form warm-up — covers sessions started before any
        // [onSheetOpened] (typing can begin as soon as the VM exists).
        val initialSession = sessionOwner
        viewModelScope.launch {
            initialSession.complete(draftStore.currentOwner())
            prefetchForm()
        }
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
     * [initialQuotes] (#604 lot 3) — the citations this opening delivers, in citation order : one
     * preview for « Citer », the whole basket for « Citer N » under the full-screen threshold.
     * Delivery happens ONCE per sheet composition (the sheet keys its effect on the VM alone) ;
     * a deliberate re-cite is a new launch, hence a new composition.
     *
     * Rendering is decided HERE per opening (#805 arbitrage, `observeQuoteCardsEnabled().first()`) :
     * cards ON → idempotent card adds (unchanged #604 behaviour) ; cards OFF (default) → the
     * `[quotemsg]` prefills are fetched now and APPENDED to the live field content at completion
     * (never a replacement computed from the row — réserve Codex n°1).
     */
    fun onSheetOpened(initialQuotes: List<QuoteSelection> = emptyList()) {
        onSheetOpened(currentPage = request.page, initialQuotes = initialQuotes)
    }

    /**
     * [currentPage] is the topic page visible at this opening. It is intentionally not part of the
     * Hilt key : the draft and in-flight submit contract remain scoped to the topic, while the HFR
     * form context is rebuilt per opening and per submit.
     */
    fun onSheetOpened(currentPage: Int, initialQuotes: List<QuoteSelection> = emptyList()) {
        materializeJob?.cancel()
        openJob?.cancel()
        updateCurrentPage(currentPage)
        prefetchForm()
        // Gate Sol #953 F2 — seal the previous session : if its owner snapshot never resolved,
        // its still-pending draft tasks must no-op rather than adopt whatever account the late
        // currentOwner() read lands on (a no-op on an already-resolved session).
        sessionOwner.cancel()
        // Gate Sol #953 F2 + #870 — mask the previous session's content NOW, synchronously :
        // the sheet must never flash account A's private text while the reopened owner's row is
        // still loading. Same one-session rule for the cards (#870) : the delivered set IS the
        // citation session, quotes armed under a previous opening never resurrect (nothing the
        // user selected is lost — since #868/#869 the hoisted basket survives until an actual
        // send, so a re-open via « Citer N » re-delivers the full selection).
        _state.update { it.copy(text = TextFieldValue(""), quotes = emptyList()) }
        // A fresh owner snapshot per opening, swapped in synchronously : everything this session
        // schedules rides THIS deferred, while tasks the previous session already scheduled keep
        // the deferred (hence the owner) of theirs.
        val opening = CompletableDeferred<String?>()
        sessionOwner = opening
        viewModelScope.launch {
            // Deliberately NOT part of [openJob] : a rapid re-open cancels the seeding below,
            // but this session's owner must still resolve for its already-scheduled tasks
            // (unless that re-open sealed the session first — complete() is then a no-op).
            opening.complete(draftStore.currentOwner())
        }
        openJob = viewModelScope.launch {
            val body = draftStore.load(opening.await(), draftKey)?.body.orEmpty()
            _state.update {
                it.copy(text = TextFieldValue(text = body, selection = TextRange(body.length)))
            }
            if (initialQuotes.isEmpty()) return@launch
            if (userPreferencesRepository.observeQuoteCardsEnabled().first()) {
                // Through onQuoteAdded : keeps the dedup + #808 cap semantics of a manual add.
                initialQuotes.forEach(::onQuoteAdded)
            } else {
                val quotes = initialQuotes.distinctBy { it.numreponse }
                // Process-restored delivery identity: a recreated sheet reloads the autosaved
                // inline BBCode without fetching and appending the same quote set a second time.
                val deliveryKey = quotes
                    .map { it.numreponse }
                    .sorted()
                    .joinToString(separator = ",")
                if (savedStateHandle.get<String>(KEY_DELIVERED_QUOTES) != deliveryKey) {
                    materializeInlineQuotes(quotes, deliveryKey)
                }
            }
        }
    }

    /**
     * #805 cards OFF — fetch the `[quotemsg]` prefills (same materializer call as the cards-ON
     * submit) and insert them into the field. The insert CONCATENATES onto the field content as
     * it is at completion time — typing during the fetch is never lost, successive citations
     * keep their chronological order, and the caret lands after the inserted quote (the natural
     * « continue typing » position). The fresh quote form also warms [loadedForm] : its hash is
     * exactly what the pre-cards flow rode at submit.
     */
    @Suppress("TooGenericExceptionCaught") // mapped to a typed error below; cancellation rethrown.
    private fun materializeInlineQuotes(quotes: List<QuoteSelection>, deliveryKey: String) {
        materializeJob?.cancel()
        val baseContext = replyContext()
        materializeJob = viewModelScope.launch {
            _state.update { it.copy(isPreparingQuotes = true, submitError = null) }
            try {
                val quoteContext = baseContext.copy(
                    quotedNumreponse = quotes.first().numreponse,
                    quoteRef = null,
                )
                val form = quoteMaterializer.fetchFormWithQuotes(
                    context = quoteContext,
                    extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                    truncate = quotes.any { it.truncate },
                )
                rememberLoadedForm(form, quoteContext.page)
                val prefills = form.initialContent.trimEnd()
                _state.update { current ->
                    val existing = current.text.text
                    // #881 — the inserted quote block ends the field here, so it carries exactly
                    // ONE trailing newline (normalised: prefills are trimEnd()'d above, never
                    // blind-appended) and the caret lands on the fresh line below the citation.
                    val combined =
                        (if (existing.isBlank()) prefills else existing.trimEnd() + "\n\n" + prefills) + "\n"
                    current.copy(
                        text = TextFieldValue(text = combined, selection = TextRange(combined.length)),
                        isPreparingQuotes = false,
                    )
                }
                savedStateHandle[KEY_DELIVERED_QUOTES] = deliveryKey
                scheduleAutosave()
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isPreparingQuotes = false) }
                throw cancelled
            } catch (_: SessionExpiredException) {
                _state.update {
                    it.copy(isPreparingQuotes = false, submitError = QuickReplySubmitError.SessionExpired)
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(isPreparingQuotes = false, submitError = QuickReplySubmitError.QuoteFetchFailed)
                }
            }
        }
    }

    /** #604 lot 2 — arm a quote card ; idempotent per numreponse (re-citing a post is a no-op). */
    fun onQuoteAdded(preview: QuoteSelection) {
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
        // #805 cards OFF — inert while the [quotemsg] insert is in flight : escalating now would
        // hand over a row without the citation the user just asked for.
        if (_state.value.isPreparingQuotes) return
        autosaveJob?.cancel()
        val session = sessionOwner
        val body = _state.value.text.text
        val quotes = _state.value.quotes
        viewModelScope.launch {
            saveDraftNow(session.await(), body)
            _effects.send(QuickReplyEffect.EscalateToFullEditor(quotes))
        }
    }

    /**
     * Dirty close — flush the pending debounce so the last keystrokes reach the row (the sheet
     * preserves, it never asks) ; runs in [viewModelScope], which outlives the sheet (the VM is
     * scoped to the topic's nav entry).
     */
    fun onDismissed() {
        // A pending [quotemsg] insert dies with the composition it belonged to (réserve Codex
        // n°2) — the user abandoned it; the flush below only persists what the field showed.
        materializeJob?.cancel()
        autosaveJob?.cancel()
        // #953 F2 — owner AND body captured NOW : the flush persists what the DISMISSED session
        // showed, under the owner of that session — regardless of what a subsequent re-opening
        // re-snapshots or masks before this write lands. If that re-opening seals this session
        // before its owner ever resolved, the await cancels the flush : a write with an unknown
        // owner never happens (gate Sol).
        val session = sessionOwner
        val body = _state.value.text.text
        viewModelScope.launch { saveDraftNow(session.await(), body) }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        // #953 F2 — same capture-at-scheduling rule as [onDismissed] : session and body belong
        // to the keystroke that scheduled this debounce, whatever happens before it fires.
        val session = sessionOwner
        val body = _state.value.text.text
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveDraftNow(session.await(), body)
        }
    }

    private suspend fun saveDraftNow(owner: String?, body: String) {
        if (body.isBlank()) {
            draftStore.delete(owner, draftKey)
        } else {
            draftStore.save(owner, draftKey, EditorDraftStore.Draft(body = body))
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

    private fun submit() {
        if (submitJob?.isActive == true) return
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        // #953 F2 — the post-success draft delete rides the owner of the session that SUBMITTED,
        // even if the sheet is reopened (and the owner re-snapshotted) while the POST is in flight.
        val session = sessionOwner
        // #974 — the cited posts ride the success effect whatever the rendering mode : inline
        // `[quotemsg]` tags of the field (cards OFF, the production default) unioned with the
        // armed cards. Snapshotted here : the state is reset on success before the effect is sent.
        val quotes = _state.value.quotes
        val quotedNumreponses = QuotedNumreponses.of(_state.value.text.text, quotes)
        val baseContext = replyContext()
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                if (quotes.isEmpty()) {
                    val form = loadedFormFor(baseContext.page)
                        ?: replyRepository.fetchReplyForm(baseContext).also {
                            rememberLoadedForm(it, baseContext.page)
                        }
                    replyRepository.submitReply(
                        context = baseContext,
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
                    val quoteContext = baseContext.copy(
                        quotedNumreponse = quotes.first().numreponse,
                        quoteRef = null,
                    )
                    val form = quoteMaterializer.fetchFormWithQuotes(
                        context = quoteContext,
                        extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                        truncate = quotes.any { it.truncate },
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
                onSuccess = { result -> handleSubmitOutcome(result, session, quotedNumreponses) },
                onFailure = ::handleSubmitFailure,
            )
        }
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

    private suspend fun handleSubmitOutcome(
        result: ReplySubmitResult,
        session: CompletableDeferred<String?>,
        quotedNumreponses: List<Int>,
    ) {
        when (result) {
            is ReplySubmitResult.Success -> {
                // Same contract as the full editor: the draft dies with the successful POST,
                // awaited so a process death cannot resurrect an already-published reply.
                autosaveJob?.cancel()
                val owner = try {
                    session.await()
                } catch (_: CancellationException) {
                    // Gate Sol #953 F2 — the session was sealed by a later re-opening before its
                    // owner ever resolved : the delete no-ops (null owner), but the success flow
                    // must still complete — HFR already accepted the reply. A genuine cancellation
                    // of THIS submit still propagates.
                    currentCoroutineContext().ensureActive()
                    null
                }
                draftStore.delete(owner, draftKey)
                savedStateHandle.remove<String>(KEY_DELIVERED_QUOTES)
                _state.update { QuickReplyUiState() }
                _effects.send(
                    QuickReplyEffect.SubmitSucceeded(
                        targetPage = result.targetPage,
                        scrollTo = result.numreponse,
                        quotedNumreponses = quotedNumreponses,
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

        /** Stable, process-restored identity of the inline quote set already delivered to the draft. */
        const val KEY_DELIVERED_QUOTES = "quickReply.deliveredQuotes"
    }
}
