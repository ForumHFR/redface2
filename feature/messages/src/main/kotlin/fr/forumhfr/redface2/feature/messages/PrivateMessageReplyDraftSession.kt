package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.filterEditorQuotes
import fr.forumhfr.redface2.core.domain.editor.insertEditorQuotes
import fr.forumhfr.redface2.core.domain.editor.mergeEditorBody
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.ui.editor.BbcodeFormatResult
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorSessionCheckpoint
import fr.forumhfr.redface2.core.ui.editor.EditorSessionField
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestoration
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestore
import fr.forumhfr.redface2.core.ui.editor.EditorSessionSurface
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ED-81/170: the reply's live buffer, owner-bound resume, restore offer and draft checkpoints.
 * Only metadata enters SavedStateHandle; private content stays in the ViewModel's draft session
 * and the owner-bound store. Form proof, submission and input guards remain with the ViewModel.
 */
internal class PrivateMessageReplyDraftSession(
    private val saved: SavedStateHandle,
    private val submission: EditorSubmissionBinding<PrivateMessageReplyEffect, EditorError>,
    private val scope: CoroutineScope,
    private val draftKey: String,
    state: PrivateMessageDraftState<PrivateMessageReplyUiState>,
    private val clearToolSelection: () -> Unit,
) {
    private val readState = state.read
    private val updateState = state.update
    val bodyFieldState = TextFieldState()
    val bodyScrollState = ScrollState(0)
    var bodyCaretRevealResetKey by mutableIntStateOf(0)
        private set
    private val restoration = EditorSessionRestoration(saved, EditorSessionSurface.MessageReply)
    val isRecreation: Boolean get() = restoration.isRecreation
    private var draftRestored = false
    private var restoredContent = false
    private var screenStopped = false
    private var stoppedDraft: EditorDraftStore.Draft? = null
    private var pendingCheckpoints = 0
    private var lastCheckpointVersion = 0L
    private var previousBody = bodyFieldState.text.toString()
    private var bodyEditedBeforeHydration = false
    private var restoredOfferAwaitingWrite: String? = null
    private var mergedDraftOffer: String? = null
    private var resolvingOffer = false
    private var decisionCompleted: CompletableDeferred<Unit>? = null

    suspend fun awaitOfferDecision() { decisionCompleted?.await() }
    private var selectedOffer: EditorDraftStore.DraftOffer? = null
    var deliveryPending = false
        private set
    private var stagedDelivery: String? = null
    private val quoteCommit = Mutex()
    private var storageConflict = false

    /** A conflicting quote write means this session's version, Q or offers are stale. */
    fun recordQuoteWrite(result: EditorDraftStore.StoreWriteResult?) {
        if (result is EditorDraftStore.StoreWriteResult.Conflict) storageConflict = true
    }

    /**
     * ED-132: after a storage conflict, Reload and Ignore first reread the row's version, Q and offers.
     * The field (C) is never replaced: a different stored body comes back as an offer (ED-121).
     */
    suspend fun reconcileAfterConflict() {
        if (!storageConflict || submission.drafts.invalidated) return
        val live = bodyFieldState.text.toString()
        val entry = submission.restore(draftKey)
        if (!submission.isInitialized) return
        storageConflict = false
        if (entry?.draft?.body == live) submission.drafts.resumeContent()
        if (submission.drafts.pendingInlineQuoteRequest == null) {
            // Q was consumed elsewhere: nothing is left to commit, the autosave owns the field again.
            deliveryPending = false
            stagedDelivery = null
            updateState { it.copy(quoteCommitPending = false) }
        }
        refreshOffers()
    }

    fun refreshOffers() {
        val offers = submission.drafts.availableOffers.takeUnless { submission.drafts.invalidated }.orEmpty()
        updateState { it.copy(draftOffers = offers, restorableDraft = offers.firstOrNull()?.draft?.body) }
        if (selectedOffer != null && offers.none { it.id == selectedOffer?.id }) {
            selectedOffer = null
            mergedDraftOffer = null
        }
    }

    /** ED-132: stage once; only the body + request CAS below can make this delivery durable. */
    fun insertQuote(body: String) {
        deliveryPending = true
        stagedDelivery = body
        val live = bodyFieldState.text.toString()
        val quote = filterEditorQuotes(body.trimEnd(), live).body
        val merge = insertEditorQuotes(live, quote.trimEnd(), submission.drafts.provenanceFor(live))
        val result = if (live.isBlank() && merge.body.isNotBlank()) merge.copy(body = merge.body + "\n") else merge
        if (result.body != live) replaceBody(result.body)
        if (live.isBlank()) resetUndoBaseline()
        previousBody = bodyFieldState.text.toString()
        submission.drafts.stageBodyMerge(result)
        updateState { it.copy(draftHydratedFromForm = true, quoteCommitPending = true) }
    }

    suspend fun commitQuote(
        request: PendingInlineQuoteRequest,
        deliveredBody: String,
    ): EditorDraftStore.StoreWriteResult = quoteCommit.withLock { commitQuoteLocked(request, deliveredBody) }

    private suspend fun commitQuoteLocked(
        request: PendingInlineQuoteRequest,
        deliveredBody: String,
    ): EditorDraftStore.StoreWriteResult {
        val snapshot = EditorDraftStore.Draft(bodyFieldState.text.toString(), isPrivate = true,
            bodyProvenance = submission.drafts.provenanceFor(bodyFieldState.text.toString()),
            offerToResolve = pendingOfferResolution())
        val result = submission.drafts.consumeInlineQuoteRequest(request, snapshot, deliveredBody)
        if (result is EditorDraftStore.StoreWriteResult.Written) deliveryPending = false
        recordQuoteWrite(result)
        updateState { it.copy(quoteCommitPending = deliveryPending) }
        submission.resolveDraftWrite(result)
        if (!deliveryPending) scheduleAutosave()
        return result
    }

    init {
        submission.drafts.checkpointObserver = { draft, version, result ->
            if (!submission.drafts.invalidated) {
                if (result == null) captureCheckpoint(draft, version)
                else restoration.completed(draft, version, result)
            }
        }
    }

    suspend fun restore() {
        restoreDraftIfAny()
        draftRestored = true
        updateState { it.copy(isInitializing = false) }
        refreshOffers()
        if (bodyEditedBeforeHydration && !restoredContent) scheduleAutosave()
    }

    /** ED-81/170: durable status wins over every offer and server prefill. */
    private suspend fun restoreDraftIfAny() {
        val entry = submission.restore(draftKey)
        val owner = submission.drafts.owner
        if (restoration.isRecreation && !restoration.belongsTo(owner, draftKey)) {
            saved.remove<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY)
        }
        restoration.bind(owner, draftKey)
        val resolution = restoration.resolve(entry, owner, draftKey)
        if (submission.blocksMutation) return
        if (resolution == EditorSessionRestore.None && !restoration.isRecreation &&
            entry?.draft?.bodyProvenance?.isQuoteOnly(entry.draft.body) == true
        ) submission.resolveDraftWrite(submission.drafts.discardQuoteOnly())
        submission.drafts.offers.firstOrNull()?.let { offerDraftRestore(it.draft) }
        if (entry == null) return
        when (resolution) {
            EditorSessionRestore.Resume, EditorSessionRestore.Uncertain -> {
                if (resolution == EditorSessionRestore.Resume) submission.drafts.resumeContent()
                val matching = restoration.matches(entry, owner, draftKey)
                restoreSessionContent(entry.draft, matching)
                if (matching) bodyFieldState.edit { selection = restoration.selection(length) }
            }
            EditorSessionRestore.Offer -> offerDraftRestore(entry.draft)
            EditorSessionRestore.None -> Unit
        }
    }

    private fun restoreSessionContent(draft: EditorDraftStore.Draft, certified: Boolean) {
        restoredContent = true
        replaceBody(draft.body)
        resetUndoBaseline()
        previousBody = draft.body
        val bodyKnown = certified || draft.body.isNotBlank()
        if (bodyKnown) restoration.decide(EditorSessionField.Body)
        updateState {
            it.copy(draftHydratedFromForm = bodyKnown,
                restorableDraft = submission.drafts.offers.firstOrNull { offer ->
                    offer.draft.body != draft.body
                }?.draft?.body)
        }
    }

    private fun offerDraftRestore(draft: EditorDraftStore.Draft) {
        val body = draft.body
        if (body.isBlank()) return
        val ignored = saved.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == draft.restoreOfferFingerprint()
        val snapshot = readState()
        val liveBody = bodyFieldState.text.toString()
        val canOffer = liveBody != body && (liveBody.isBlank() || liveBody == snapshot.draftHydratedContent)
        if (!ignored && canOffer) updateState { it.copy(restorableDraft = body) }
    }

    fun onBodyChanged(body: String) {
        updateState { it.copy(bodyText = body) }
        if (body != previousBody) {
            decideBody()
            previousBody = body
            scheduleAutosave()
        }
    }

    fun decideBody() {
        bodyEditedBeforeHydration = true
        restoration.decide(EditorSessionField.Body)
    }

    private fun replaceBody(body: String) {
        clearToolSelection()
        Snapshot.withMutableSnapshot {
            bodyCaretRevealResetKey++
            bodyFieldState.edit {
                replace(0, length, body)
                selection = TextRange(length)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun resetUndoBaseline() {
        // A late quote must preserve edits made while the form was loading, including deleted text.
        if (!bodyEditedBeforeHydration) bodyFieldState.undoState.clearHistory()
    }

    /** Ordinary form hydration only; durable Q is an insertion through insertQuote, never a seed. */
    fun hydrateBody(initial: String) {
        if (readState().draftHydratedFromForm) return
        val existing = bodyFieldState.text.toString()
        val hydrate = restoration.canHydrate(EditorSessionField.Body, existing.isBlank())
        // A fresh opening quote is an insertion around live edits (R0c), not a restored default.
        val insertOpeningQuote = !restoredContent
        if (initial.isNotBlank() && (hydrate || insertOpeningQuote)) {
            bodyEditedBeforeHydration = bodyEditedBeforeHydration || existing.isNotEmpty()
            val separator = if (initial.endsWith('\n')) "\n" else "\n\n"
            Snapshot.withMutableSnapshot {
                if (existing.isBlank()) bodyCaretRevealResetKey++
                clearToolSelection()
                bodyFieldState.editBbcode { _, _, _ ->
                    val combined = if (existing.isBlank()) initial else initial + separator + existing
                    BbcodeFormatResult(combined, combined.length, combined.length)
                }
            }
            resetUndoBaseline()
            previousBody = bodyFieldState.text.toString()
        }
        updateState {
            it.copy(
                draftHydratedFromForm = true,
                draftHydratedContent = initial.takeIf { hydrate || insertOpeningQuote }.orEmpty(),
            )
        }
        if (initial.isNotBlank() && existing.isNotBlank()) scheduleAutosave()
    }

    private fun selectOffer(offerId: String?): String? {
        val snapshot = readState()
        selectedOffer = if (offerId == null) snapshot.draftOffers.firstOrNull()
            else snapshot.draftOffers.firstOrNull { it.id == offerId }
        val legacy = snapshot.restorableDraft.takeIf { offerId == null && snapshot.draftOffers.isEmpty() }
        return selectedOffer?.draft?.body ?: legacy
    }

    /** ED-89: D, retained delivered quotes, then live typing; apply one undoable mutation. */
    fun restoreOfferedDraft(offerId: String? = null) {
        if (resolvingOffer || deliveryPending) return
        val offeredBody = selectOffer(offerId) ?: return
        val liveBody = bodyFieldState.text.toString()
        val merge = mergeEditorBody(offeredBody, liveBody, provenance = submission.drafts.provenanceFor(liveBody))
        val needsMerge = mergedDraftOffer != offeredBody
        mergedDraftOffer = offeredBody
        restoredOfferAwaitingWrite = offeredBody
        if (needsMerge) replaceBody(merge.body)
        if (needsMerge) submission.drafts.stageBodyMerge(merge)
        restoration.decide(EditorSessionField.Body)
        updateState { it.copy(restorableDraft = null, draftHydratedFromForm = true) }
        resolveOffer(offeredBody, restoring = true)
    }

    private fun resolveOffer(body: String, restoring: Boolean = false) {
        if (resolvingOffer) return
        resolvingOffer = true
        decisionCompleted = CompletableDeferred()
        scope.launch {
            try {
                submission.flush(null)
                val offer = selectedOffer ?: submission.drafts.offered(body)
                val live = bodyFieldState.text.toString()
                val provenance = submission.drafts.provenanceFor(live)
                val draft = EditorDraftStore.Draft(body = live, isPrivate = true, bodyProvenance = provenance,
                    offerToResolve = offer.takeIf { restoring })
                val result = if (offer != null && !restoring) submission.drafts.resolveOffer(offer, draft)
                    else submission.persist(draft)
                submission.resolveDraftWrite(result)
                if (result !is EditorDraftStore.StoreWriteResult.Written && !submission.drafts.invalidated) {
                    updateState { it.copy(restorableDraft = body) }
                } else if (result is EditorDraftStore.StoreWriteResult.Written) {
                    if (!restoring) saved[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] =
                        EditorDraftStore.Draft(body = body, isPrivate = true).restoreOfferFingerprint()
                    restoredOfferAwaitingWrite = null
                    mergedDraftOffer = null
                    refreshOffers()
                }
            } finally {
                restoredOfferAwaitingWrite = null
                resolvingOffer = false
                decisionCompleted?.complete(Unit)
                decisionCompleted = null
                scheduleAutosave()
            }
        }
    }

    /** ED-121: consume only this offer, preserving live input and any pending request. */
    fun discardOfferedDraft(offerId: String? = null) {
        if (resolvingOffer || deliveryPending) return
        val body = selectOffer(offerId) ?: return
        updateState { it.copy(restorableDraft = null) }
        resolveOffer(body)
    }

    /** A failed GET or an undecided offer cannot certify an unknown/placeholder body (#1574). */
    fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = readState()
        val liveBody = bodyFieldState.text.toString()
        val bodyKnown = snapshot.draftHydratedFromForm || bodyEditedBeforeHydration
        val undecidedOffer = snapshot.restorableDraft != null &&
            (liveBody.isBlank() || liveBody == snapshot.draftHydratedContent)
        if (deliveryPending || !restoration.canCheckpoint(bodyKnown, undecidedOffer)) return null
        val provenance = submission.drafts.provenanceFor(liveBody)
        return EditorDraftStore.Draft(body = liveBody, isPrivate = true, bodyProvenance = provenance,
            offerToResolve = pendingOfferResolution())
    }

    /**
     * ED-121: the D merged into the field by a Restore whose write has not succeeded yet. Every later
     * write of C (autosave, quote delivery, send) carries it, so a merged D never stays offered.
     */
    fun pendingOfferResolution(): EditorDraftStore.DraftOffer? {
        val restoring = restoredOfferAwaitingWrite ?: mergedDraftOffer
        return selectedOffer?.takeIf { restoring != null } ?: restoring?.let(submission.drafts::offered)
    }

    /**
     * ED-132: a delivered quote whose C/Q transaction is pending or failed seals [draftSnapshot].
     * A close settles it first, then saves the field as it is now, typing made since included.
     */
    suspend fun flushForClose() {
        quoteCommit.withLock {
            val pending = submission.drafts.pendingInlineQuoteRequest
            val delivered = stagedDelivery
            if (deliveryPending && pending != null && delivered != null) commitQuoteLocked(pending, delivered)
        }
        submission.flush(::draftSnapshot)
    }

    /** Debounced while visible, immediate while stopped; a stale owner never writes private content. */
    fun scheduleAutosave() {
        if (!draftRestored || resolvingOffer) return
        if (screenStopped) {
            if (stoppedDraft != draftSnapshot()) persistDraftNow()
        } else submission.autosave(::draftSnapshot)
    }

    fun onStarted() { screenStopped = false }
    fun onStopped() {
        screenStopped = true
        persistDraftNow()
    }

    /** Capture live input before suspension, even before the text mirror or debounce has run. */
    fun persistDraftNow() {
        if (!draftRestored || submission.blocksClose || submission.drafts.invalidated) return
        val draft = draftSnapshot() ?: return
        // Consume the body decision before suppressing this revision's delayed mirror.
        if (draft.body != previousBody) decideBody()
        // This live revision is checkpointed now; its delayed mirror must not rearm autosave.
        previousBody = draft.body
        val version = maxOf(submission.drafts.nextContentVersion, lastCheckpointVersion + 1)
        lastCheckpointVersion = version
        pendingCheckpoints++
        stoppedDraft = draft
        captureSessionCheckpoint(draft, version)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = submission.flush(::draftSnapshot)
                restoration.completed(draft, version, result)
            } finally {
                pendingCheckpoints--
                if (pendingCheckpoints == 0) lastCheckpointVersion = 0L
            }
        }
    }

    private fun captureCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        if (version >= lastCheckpointVersion &&
            draft.body == bodyFieldState.text.toString()
        ) captureSessionCheckpoint(draft, version)
    }

    private fun captureSessionCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        restoration.capture(
            EditorSessionCheckpoint(draft, version, bodyFieldState.selection,
                restoration.scrollToRestore ?: bodyScrollState.value), bodyKnown = true,
        )
    }

    /** The retained VM already owns its scroll on rotation; process restoration waits for layout. */
    suspend fun restoreBodyScroll() {
        restoration.scrollToRestore?.let { position ->
            val target = position.coerceAtMost(bodyScrollState.maxValue)
            bodyScrollState.scrollTo(target)
            if (bodyScrollState.value == target) restoration.scrollRestored()
        }
    }

    fun invalidate() = restoration.invalidate()

    private companion object {
        const val DRAFT_RESTORE_OFFER_FINGERPRINT_KEY = "draft_restore_offer_fingerprint"
    }
}
