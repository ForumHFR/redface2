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

/** ED-81/170: private content stays in the owner-bound store; the Bundle holds metadata only. */
internal class PrivateMessageComposeDraftSession(
    private val saved: SavedStateHandle,
    private val submission: EditorSubmissionBinding<PrivateMessageComposeEffect, EditorError>,
    private val scope: CoroutineScope,
    private val draftKey: String,
    private val readState: () -> PrivateMessageComposeUiState,
    private val updateState: ((PrivateMessageComposeUiState) -> PrivateMessageComposeUiState) -> Unit,
) {
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
        draftRestored = true
        updateState { it.copy(isInitializing = false) }
        if (edited) scheduleAutosave()
    }

    private fun applyResolution(entry: EditorDraftStore.DraftEntry, resolution: EditorSessionRestore) {
        try {
            when (resolution) {
                EditorSessionRestore.Resume, EditorSessionRestore.Uncertain -> restoreSessionContent(entry)
                EditorSessionRestore.Offer -> offer(entry.draft)
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

    private fun offer(draft: EditorDraftStore.Draft) {
        val recipients = EditorRecipientState.decode(draft.recipients)
        val snapshot = readState()
        val hasContent = draft.body.isNotBlank() || !draft.subject.isNullOrBlank() || display(recipients).isNotBlank()
        val matches = draft.body == bodyFieldState.text.toString() &&
            draft.subject.orEmpty() == snapshot.subject && recipients == snapshot.recipientState
        val untouched = bodyFieldState.text.isBlank() && snapshot.subject.isBlank() && !edited
        val ignored = saved.get<String>(IGNORED_OFFER) == draft.restoreOfferFingerprint()
        val shouldOffer = hasContent && !matches
        if (shouldOffer && untouched && !ignored) {
            updateState {
                it.copy(restorableDraft = draft.body, restorableSubject = draft.subject,
                    restorableRecipients = draft.recipients)
            }
        }
    }

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

    fun changeRecipients(value: EditorRecipientState, legacyDisplay: String = display(value)) {
        decide(EditorSessionField.Recipients)
        updateState {
            it.copy(recipientState = value, recipients = legacyDisplay,
                restorableRecipients = if (it.restorableDraft != null) value.encode() else it.restorableRecipients)
        }
        scheduleAutosave()
    }

    fun changeSubject(value: String) {
        decide(EditorSessionField.Subject)
        updateState {
            it.copy(subject = value,
                restorableSubject = if (it.restorableDraft != null) value else it.restorableSubject)
        }
        scheduleAutosave()
    }

    fun restoreOfferedDraft() {
        val snapshot = readState()
        val offered = snapshot.restorableDraft ?: return
        val live = bodyFieldState.text.toString()
        if (offered.isNotBlank()) {
            replaceBody(if (live.isBlank() || live == offered) offered else live.trimEnd() + "\n\n" + offered)
            decide(EditorSessionField.Body)
        }
        val recipients = EditorRecipientState.decode(snapshot.restorableRecipients)
        restoration.decide(EditorSessionField.Subject)
        restoration.decide(EditorSessionField.Recipients)
        updateState {
            val next = if (it.recipients.isBlank()) it.withRecipients(recipients) else it
            next.copy(subject = it.subject.ifBlank { it.restorableSubject.orEmpty() },
                bodyKnown = it.bodyKnown || offered.isNotBlank(),
                restorableDraft = null, restorableSubject = null, restorableRecipients = null)
        }
        scheduleAutosave()
    }

    fun discardOfferedDraft() {
        val snapshot = readState()
        val body = snapshot.restorableDraft ?: return
        saved[IGNORED_OFFER] = EditorDraftStore.Draft(body, snapshot.restorableSubject,
            snapshot.restorableRecipients, isPrivate = true).restoreOfferFingerprint()
        updateState { it.copy(restorableDraft = null, restorableSubject = null, restorableRecipients = null) }
        scope.launch { submission.flush(EditorDraftStore.Draft(body = "", isPrivate = true)) }
    }

    fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = readState()
        val body = bodyFieldState.text.toString()
        val undecided = body.isBlank() && snapshot.restorableDraft != null
        if (unreadableRecipients || !restoration.canCheckpoint(snapshot.bodyKnown, undecided)) return null
        return EditorDraftStore.Draft(body, snapshot.subject.ifBlank { null }, snapshot.recipientState.encode(),
            isPrivate = true)
    }

    private fun replaceBody(body: String) {
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
    private fun persistDraftNow() {
        if (!draftRestored || submission.blocksClose || submission.drafts.invalidated) return
        val draft = draftSnapshot() ?: return
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
