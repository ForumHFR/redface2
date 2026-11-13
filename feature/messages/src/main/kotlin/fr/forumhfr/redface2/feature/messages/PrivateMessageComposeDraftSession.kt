package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.mergeEditorBody
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorSessionCheckpoint
import fr.forumhfr.redface2.core.ui.editor.EditorSessionField
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestoration
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestore
import fr.forumhfr.redface2.core.ui.editor.EditorSessionSurface
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * ED-121: headers of a new private message that are worth offering over a blank body: a subject, or
 * at least one named recipient. An empty envelope names nobody, and neither does an unreadable one.
 */
internal fun composeDraftHoldsHeaders(draft: EditorDraftStore.Draft): Boolean =
    !draft.subject.isNullOrBlank() || readableRecipients(draft.recipients)?.isBlank == false

/** Null when the stored value cannot be decoded; the stored format is never rewritten here. */
private fun readableRecipients(stored: String?): EditorRecipientState? = try {
    EditorRecipientState.decode(stored)
} catch (_: IOException) {
    null
}

private val EditorRecipientState.isBlank: Boolean get() = chips.isEmpty() && buffer.isEmpty()

/** ED-81/170: private content stays in the owner-bound store; the Bundle holds metadata only. */
internal class PrivateMessageComposeDraftSession(
    private val saved: SavedStateHandle,
    private val submission: EditorSubmissionBinding<PrivateMessageComposeEffect, EditorError>,
    private val scope: CoroutineScope,
    private val draftKey: String,
    state: PrivateMessageDraftState<PrivateMessageComposeUiState>,
    private val clearToolSelection: () -> Unit,
) {
    private val readState = state.read
    private val updateState = state.update
    val bodyFieldState = TextFieldState()
    val bodyScrollState = ScrollState(0)
    var bodyCaretRevealResetKey by mutableIntStateOf(0)
        private set
    private val restoration = EditorSessionRestoration(saved, EditorSessionSurface.MessageCompose)
    val isRecreation: Boolean get() = restoration.isRecreation
    private var draftRestored = false
    private var edited = false
    private var screenStopped = false
    private var stoppedDraft: EditorDraftStore.Draft? = null
    private var pendingCheckpoints = 0
    private var lastCheckpointVersion = 0L
    private var previousBody = ""
    private var unreadableRecipients = false
    /** ED-121: the user changed the subject or the recipients. A prefilled recipient is not typing. */
    private var headersEdited = false
    /** ED-121: the offered draft a Restore merged into the fields, until a write of C has consumed it. */
    private var mergedOffer: EditorDraftStore.DraftOffer? = null
    /** ED-121: the offer whose decision is being written. Hidden meanwhile; one decision at a time. */
    private var decidingOffer: EditorDraftStore.DraftOffer? = null

    init {
        submission.drafts.checkpointObserver = { draft, version, result ->
            if (!submission.drafts.invalidated) {
                if (result == null) captureCheckpoint(draft, version)
                else restoration.completed(draft, version, result)
            }
        }
    }

    suspend fun restore(initialRecipient: String?) {
        val entry = submission.restore(draftKey)
        if (submission.drafts.invalidated) {
            updateState { it.copy(isInitializing = false) }
            return
        }
        val owner = submission.drafts.owner
        if (isRecreation && !restoration.belongsTo(owner, draftKey)) saved.remove<String>(IGNORED_OFFER)
        restoration.bind(owner, draftKey)
        val resolution = restoration.resolve(entry, owner, draftKey)
        if (entry != null && !submission.blocksMutation) applyResolution(entry, resolution)
        // Navigation is a default, not a competing source for a resumed or explicitly cleared field.
        hydrateRecipients(initialRecipient.orEmpty().trim())
        refreshOffers()
        draftRestored = true
        updateState { it.copy(isInitializing = false) }
        if (edited) scheduleAutosave()
    }

    private fun applyResolution(entry: EditorDraftStore.DraftEntry, resolution: EditorSessionRestore) {
        try {
            when (resolution) {
                EditorSessionRestore.Resume, EditorSessionRestore.Uncertain -> {
                    // ED-121: a certified resume is this composition's own C, never an offered D.
                    if (resolution == EditorSessionRestore.Resume) submission.drafts.resumeContent()
                    restoreSessionContent(entry)
                }
                // The offers come from the session (refreshOffers); an unreadable row still seals writes.
                EditorSessionRestore.Offer -> { EditorRecipientState.decode(entry.draft.recipients) }
                EditorSessionRestore.None -> Unit
            }
        } catch (_: IOException) {
            unreadableRecipients = true
            restoration.invalidate()
            updateState { it.copy(submitError = EditorErrors.draftSaveFailed) }
        }
    }

    private fun restoreSessionContent(entry: EditorDraftStore.DraftEntry) {
        val draft = entry.draft
        val recipients = EditorRecipientState.decode(draft.recipients)
        val certified = restoration.matches(entry, submission.drafts.owner, draftKey)
        replaceBody(draft.body)
        if (certified) bodyFieldState.edit { selection = restoration.selection(length) }
        resetUndoBaseline()
        previousBody = draft.body
        val bodyKnown = certified || draft.body.isNotBlank()
        if (bodyKnown) restoration.decide(EditorSessionField.Body)
        if (certified || !draft.subject.isNullOrBlank()) restoration.decide(EditorSessionField.Subject)
        restoration.decide(EditorSessionField.Recipients)
        updateState {
            it.withRecipients(recipients).copy(subject = draft.subject.orEmpty(), bodyKnown = bodyKnown,
                restorableDraft = null, restorableSubject = null, restorableRecipients = null)
        }
    }

    /**
     * ED-121: the drafts still offered for a new message, most recent first. The row found at
     * opening and the versions already copied aside are listed together; the first one feeds the
     * single-offer fields. An offer identical to the fields is hidden, never deleted, and comes back
     * once they differ. The offer being decided is hidden until its write returns: a refused write
     * shows it again. An offer that shows nothing (raw fallback of the store) is never listed.
     */
    fun refreshOffers() {
        val usable = !unreadableRecipients && !submission.drafts.invalidated
        val available = submission.drafts.availableOffers.takeIf { usable }.orEmpty()
        if (available.none { it.id == mergedOffer?.id }) mergedOffer = null
        val deciding = decidingOffer?.id
        val merged = mergedOffer?.id
        updateState { current ->
            val offers = available.filterNot { offer ->
                val identical = offer.id != merged && current.matches(offer.draft)
                offer.id == deciding || identical || !offer.draft.holdsContent()
            }
            val first = offers.firstOrNull()?.draft
            current.copy(draftOffers = offers, restorableDraft = first?.body, restorableSubject = first?.subject,
                restorableRecipients = first?.recipients)
        }
    }

    private fun EditorDraftStore.Draft.holdsContent(): Boolean = body.isNotBlank() || composeDraftHoldsHeaders(this)

    private fun PrivateMessageComposeUiState.matches(draft: EditorDraftStore.Draft): Boolean =
        draft.body == bodyFieldState.text.toString() && draft.subject.orEmpty() == subject &&
            readableRecipients(draft.recipients) == recipientState

    fun decide(field: EditorSessionField) {
        edited = true
        restoration.decide(field)
        if (field == EditorSessionField.Body) updateState { it.copy(bodyKnown = true) }
    }

    fun onBodyChanged(body: String) {
        updateState { it.copy(bodyText = body) }
        if (body != previousBody) {
            decide(EditorSessionField.Body)
            previousBody = body
            scheduleAutosave()
        }
    }

    fun hydrate(form: ReplyForm) {
        val snapshot = readState()
        if (submission.blocksMutation) return
        if (restoration.canHydrate(EditorSessionField.Body, bodyFieldState.text.isBlank())) {
            replaceBody(form.initialContent)
            previousBody = form.initialContent
            resetUndoBaseline()
        }
        if (restoration.canHydrate(EditorSessionField.Subject, snapshot.subject.isBlank())) {
            updateState { it.copy(subject = form.hiddenFields["sujet"].orEmpty()) }
        }
        hydrateRecipients(form.hiddenFields["dest"].orEmpty())
        updateState { it.copy(bodyKnown = true) }
        refreshOffers()
        if (edited) scheduleAutosave()
    }

    private fun hydrateRecipients(value: String) {
        val snapshot = readState()
        val hydrate = restoration.canHydrate(EditorSessionField.Recipients, snapshot.recipients.isBlank())
        if (hydrate && value.isNotBlank() && snapshot.submission !is EditorSubmissionState.Uncertain) {
            val recipients = EditorRecipientState(value.split(',').map(String::trim).filter(String::isNotBlank))
            updateState { it.withRecipients(recipients) }
        }
    }

    /** ED-121: an offer is an immutable version; typing a header never rewrites what it shows. */
    fun changeRecipients(value: EditorRecipientState) {
        decide(EditorSessionField.Recipients)
        headersEdited = true
        updateState { it.copy(recipientState = value, recipients = display(value)) }
        scheduleAutosave()
    }

    fun changeSubject(value: String) {
        decide(EditorSessionField.Subject)
        headersEdited = true
        updateState { it.copy(subject = value) }
        scheduleAutosave()
    }

    private fun PrivateMessageComposeUiState.draftOffer(id: String?): EditorDraftStore.DraftOffer? =
        if (id == null) draftOffers.firstOrNull() else draftOffers.firstOrNull { it.id == id }

    /**
     * ED-121: merge D into the fields once (ED-89), then write C and consume D in one transaction.
     * A refused write leaves D offered; deciding it again persists the fields without a second merge,
     * and any later write of C consumes it too ([pendingOfferResolution]).
     */
    fun restoreOfferedDraft(offerId: String? = null) {
        val offer = readState().draftOffer(offerId)
        if (offer == null || decidingOffer != null) return
        if (mergedOffer?.id != offer.id) applyOffer(offer.draft)
        mergedOffer = offer
        decideOffer(offer) { submission.persist(liveDraft()) }
    }

    /** ED-89: D then the live body, as one edit; live recipients and a live subject always win. */
    private fun applyOffer(offered: EditorDraftStore.Draft) {
        val live = bodyFieldState.text.toString()
        if (offered.body.isNotBlank()) {
            replaceBody(mergeEditorBody(offered.body, live).body)
            decide(EditorSessionField.Body)
        }
        val recipients = readableRecipients(offered.recipients)
        restoration.decide(EditorSessionField.Subject)
        restoration.decide(EditorSessionField.Recipients)
        updateState {
            val next = if (it.recipients.isBlank() && recipients != null) it.withRecipients(recipients) else it
            next.copy(subject = it.subject.ifBlank { offered.subject.orEmpty() },
                bodyKnown = it.bodyKnown || offered.body.isNotBlank(),
                restorableDraft = null, restorableSubject = null, restorableRecipients = null)
        }
    }

    /** ED-121: « Ignorer » consumes this offer only; the fields are written as they are, never blanked. */
    fun discardOfferedDraft(offerId: String? = null) {
        val offer = readState().draftOffer(offerId)
        if (offer == null || decidingOffer != null) return
        saved[IGNORED_OFFER] = offer.draft.restoreOfferFingerprint()
        decideOffer(offer) {
            submission.drafts.resolveOffer(offer, liveDraft().copy(offerToResolve = null))
                .also(submission::resolveDraftWrite)
        }
    }

    private fun decideOffer(
        offer: EditorDraftStore.DraftOffer,
        write: suspend () -> EditorDraftStore.StoreWriteResult?,
    ) {
        decidingOffer = offer
        refreshOffers()
        scope.launch {
            var written = false
            try {
                submission.flush(null)
                written = write() is EditorDraftStore.StoreWriteResult.Written
            } finally {
                decidingOffer = null
                refreshOffers()
                // A refused decision keeps the fields; the next autosave retries their write.
                if (!written) scheduleAutosave()
            }
        }
    }

    /** ED-121: a merged offer that is still offered; every write of C carries it until it is consumed. */
    fun pendingOfferResolution(): EditorDraftStore.DraftOffer? = submission.drafts.available(mergedOffer)

    /** The fields as they are: an explicit decision on an offer writes C, whatever was typed. */
    private fun liveDraft(): EditorDraftStore.Draft {
        val fields = readState()
        return EditorDraftStore.Draft(bodyFieldState.text.toString(), fields.subject.ifBlank { null },
            fields.recipientState.encode(), isPrivate = true, offerToResolve = pendingOfferResolution())
    }

    /**
     * ED-121: fields that hold nothing typed never replace an undecided offer, on close included.
     * A typed subject or recipient is content of C even over a blank body: it is written like a
     * body, and the store first copies the offered draft aside, so the offer is not overwritten.
     */
    fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = readState()
        val body = bodyFieldState.text.toString()
        val undecided = snapshot.restorableDraft != null && body.isBlank() && !headersEdited
        if (unreadableRecipients || !restoration.canCheckpoint(snapshot.bodyKnown, undecided)) return null
        return EditorDraftStore.Draft(body, snapshot.subject.ifBlank { null }, snapshot.recipientState.encode(),
            isPrivate = true, offerToResolve = pendingOfferResolution())
    }

    /**
     * ED-10: the awaited last save of a close. False when recipients, subject or body hold content
     * that is not durable, so the caller asks before abandoning it. Recipients compare decoded: a
     * historical CSV row, an absent row and the envelope written today can all name the same list.
     */
    suspend fun flushForClose(): Boolean = submission.flushForClose(
        live = {
            val fields = readState()
            EditorDraftStore.Draft(bodyFieldState.text.toString(), fields.subject, fields.recipientState.encode(),
                isPrivate = true)
        },
        recipients = ::decodedRecipients,
        snapshot = ::draftSnapshot,
    )

    /** An unreadable value only equals itself; the stored format is never rewritten here. */
    private fun decodedRecipients(stored: String): Any = try {
        EditorRecipientState.decode(stored)
    } catch (_: IOException) {
        stored
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
        if (!edited) bodyFieldState.undoState.clearHistory()
    }

    fun scheduleAutosave() {
        if (!draftRestored) return
        if (screenStopped) {
            if (stoppedDraft != draftSnapshot()) persistDraftNow()
        } else submission.autosave(::draftSnapshot)
    }

    fun onStarted() { screenStopped = false }
    fun onStopped() {
        screenStopped = true
        persistDraftNow()
    }

    /** Capture before suspension, including the final keystroke before the mirror/debounce runs. */
    fun persistDraftNow() {
        if (!draftRestored || submission.blocksClose || submission.drafts.invalidated) return
        val draft = draftSnapshot() ?: return
        // Consume the body decision before suppressing this revision's delayed mirror.
        if (draft.body != previousBody) decide(EditorSessionField.Body)
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
        if (version >= lastCheckpointVersion && draft == draftSnapshot()) captureSessionCheckpoint(draft, version)
    }

    private fun captureSessionCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        restoration.capture(EditorSessionCheckpoint(draft, version, bodyFieldState.selection,
            restoration.scrollToRestore ?: bodyScrollState.value), bodyKnown = true)
    }

    suspend fun restoreBodyScroll() {
        restoration.scrollToRestore?.let { position ->
            val target = position.coerceAtMost(bodyScrollState.maxValue)
            bodyScrollState.scrollTo(target)
            if (bodyScrollState.value == target) restoration.scrollRestored()
        }
    }

    fun invalidate() = restoration.invalidate()

    private fun PrivateMessageComposeUiState.withRecipients(value: EditorRecipientState) =
        copy(recipientState = value, recipients = display(value))

    private fun display(value: EditorRecipientState): String =
        (value.chips + listOf(value.buffer).filter(String::isNotEmpty)).joinToString(",")

    private companion object {
        const val IGNORED_OFFER = "messageComposeEditor.ignoredOfferFingerprint"
    }
}
