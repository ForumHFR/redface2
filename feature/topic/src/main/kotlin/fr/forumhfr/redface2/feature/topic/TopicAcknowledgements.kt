package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val TOPIC_SUBMIT_ACK_WAIT_MILLIS = 1_000L

internal data class TopicAcknowledgement(
    val id: Long,
    val account: String,
    val kind: Kind,
    val lastPage: Int? = null,
    val refreshFailed: Boolean = false,
    val expiresAtMillis: Long? = null,
    val revision: Int = 0,
    val offerOnly: Boolean = false,
) {
    enum class Kind { Published, Edited, Deleted, RefreshWarning }
}

/**
 * Save acceptance before consuming the relay. The 1 s deadline confirms acceptance, but only the
 * first terminal refresh closes it. A Published acknowledgement carries its last-page offer from
 * acceptance when the known pager already proves it (#1437): the confirmation and the offer are
 * then one presentation, and a late refresh only retargets the destination in place. Without a
 * prediction, it may still gain one late last-page offer (a page the refresh alone revealed).
 * Identity fences departures/accounts/new submissions; revision fences callbacks from replaced
 * presentations. An offer's accessible expiry starts only when it actually acquires the host.
 * Restoring a known offer never repeats a confirmation already presented by the previous process.
 */
internal class TopicAcknowledgements(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private enum class Phase { Waiting, Deadline, Finished }

    private var sequence = saved.get<Long>(KEY_SEQUENCE) ?: 0L
    private var accepted: TopicAcknowledgement? = null
    private var wasPresented = false
    private var phase = Phase.Finished
    private var confirmationExpiresAtMillis: Long? = null
    private var waitUntilMillis: Long? = null
    private var deadlineJob: Job? = null
    private val mutableFeedback = MutableStateFlow<TopicAcknowledgement?>(null)
    val feedback = mutableFeedback.asStateFlow()
    private val mutableInvalidations = MutableStateFlow(0)
    val invalidations = mutableInvalidations.asStateFlow()

    init {
        restore()
    }

    /**
     * [predictedLastPage] is a last page the caller already knows to lie beyond the submit target.
     * An edit never offers a destination, whatever the caller predicted.
     */
    fun accept(account: String, operation: PostSubmitOperation, predictedLastPage: Int? = null): Long {
        clear()
        val kind = if (operation == PostSubmitOperation.Edit) {
            TopicAcknowledgement.Kind.Edited
        } else {
            TopicAcknowledgement.Kind.Published
        }
        val offered = predictedLastPage?.takeIf { kind == TopicAcknowledgement.Kind.Published && it > 0 }
        val ack = TopicAcknowledgement(nextId(), account, kind, lastPage = offered)
        accepted = ack
        wasPresented = false
        phase = Phase.Waiting
        waitUntilMillis = now() + TOPIC_SUBMIT_ACK_WAIT_MILLIS
        saveAcknowledgement()
        awaitDeadline(ack.id)
        return ack.id
    }

    private fun awaitDeadline(id: Long) {
        val deadline = waitUntilMillis ?: now()
        deadlineJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(0))
            confirmAtDeadline(id)
        }
    }

    private fun confirmAtDeadline(id: Long) {
        val ack = accepted?.takeIf { it.id == id } ?: return
        if (phase != Phase.Waiting) return
        phase = Phase.Deadline
        mutableFeedback.value = ack
        saveAcknowledgement()
    }

    fun refreshed(id: Long, lastPage: Int?) {
        val ack = accepted?.takeIf { it.id == id } ?: return
        if (phase == Phase.Finished) return
        val late = phase == Phase.Deadline
        phase = Phase.Finished
        val page = lastPage.takeIf { ack.kind == TopicAcknowledgement.Kind.Published }
        when {
            // Nothing was published yet: the refreshed pager wins over the prediction, both ways.
            !late -> {
                deadlineJob?.cancel()
                settle(ack.copy(lastPage = page))
            }
            // The predicted offer already went out with the confirmation: never present it again.
            // Its expiry job (which replaced the deadline job) must keep running.
            ack.lastPage != null -> retargetPredictedOffer(ack, page)
            page != null -> {
                val confirmationVisible = confirmationExpiresAtMillis?.let { it > now() } == true
                settle(
                    ack.copy(
                        lastPage = page,
                        revision = ack.revision + 1,
                        offerOnly = wasPresented && !confirmationVisible,
                    ),
                )
            }
        }
        saveAcknowledgement()
    }

    private fun settle(ready: TopicAcknowledgement) {
        accepted = ready
        mutableFeedback.value = ready
    }

    /**
     * Same id and revision: the host keeps its snackbar and only the action's destination moves.
     * A refresh that no longer finds a later page leaves the predicted offer alone; its click
     * resolves the real tail anyway.
     */
    private fun retargetPredictedOffer(ack: TopicAcknowledgement, page: Int?) {
        if (page == null || page == ack.lastPage) return
        accepted = ack.copy(lastPage = page)
        val shown = feedback.value?.takeIf { it.id == ack.id && it.lastPage != null } ?: return
        mutableFeedback.value = shown.copy(lastPage = page)
    }

    fun refreshFailed(id: Long) {
        val ack = accepted?.takeIf { it.id == id } ?: return
        if (phase == Phase.Finished) return
        deadlineJob?.cancel()
        phase = Phase.Finished
        mutableFeedback.value = if (wasPresented) {
            TopicAcknowledgement(nextId(), ack.account, TopicAcknowledgement.Kind.RefreshWarning)
        } else {
            // No destination survives a failed refresh, predicted or not.
            ack.copy(lastPage = null, refreshFailed = true, revision = ack.revision + 1).also { accepted = it }
        }
        saveAcknowledgement()
    }

    fun deleted(account: String) {
        reset()
        mutableFeedback.value = TopicAcknowledgement(nextId(), account, TopicAcknowledgement.Kind.Deleted)
        saveAcknowledgement()
    }

    fun refreshWarning(account: String) {
        mutableFeedback.value = TopicAcknowledgement(nextId(), account, TopicAcknowledgement.Kind.RefreshWarning)
        saveAcknowledgement()
    }

    fun forPresentation(id: Long, revision: Int? = null): TopicAcknowledgement? {
        val ack = current(id, revision) ?: return null
        return if (ack.expiresAtMillis?.let { it <= now() } == true) {
            dismiss(id, ack.revision)
            null
        } else {
            ack
        }
    }

    /** Called only when this revision owns the host mutex. Consumption never cancels display. */
    fun presented(id: Long, timeoutMillis: Long, revision: Int? = null) {
        val ack = current(id, revision) ?: return
        if (accepted?.id == id) wasPresented = true
        val startedAt = now()
        val deadline = ack.expiresAtMillis ?: (startedAt + timeoutMillis.coerceAtMost(Long.MAX_VALUE - startedAt))
        if (ack.lastPage == null) {
            confirmationExpiresAtMillis = deadline
            mutableFeedback.value = null
        } else {
            mutableFeedback.value = ack.copy(expiresAtMillis = deadline)
            deadlineJob?.cancel()
            deadlineJob = scope.launch {
                delay((deadline - now()).coerceAtLeast(0))
                dismiss(id, ack.revision)
            }
        }
        saveAcknowledgement()
    }

    fun remainingMillis(id: Long, revision: Int? = null): Long? = current(id, revision)
        ?.expiresAtMillis?.let { (it - now()).coerceAtLeast(0) }

    fun takeOffer(id: Long, account: String, revision: Int? = null): Int? {
        val ack = forPresentation(id, revision) ?: return null
        return if (ack.account.equals(account, ignoreCase = true) && ack.expiresAtMillis != null) {
            dismiss(id, ack.revision)
            ack.lastPage
        } else {
            null
        }
    }

    /** Old host callbacks must not dismiss a newer revision of the same accepted submission. */
    fun dismiss(id: Long, revision: Int? = null) {
        if (accepted?.matches(id, revision) == true) confirmationExpiresAtMillis = null
        if (current(id, revision) == null) return
        mutableFeedback.value = null
        saveAcknowledgement()
    }

    private fun current(id: Long, revision: Int?) = feedback.value?.takeIf { it.matches(id, revision) }

    private fun TopicAcknowledgement.matches(id: Long, revision: Int?) =
        this.id == id && (revision == null || this.revision == revision)

    fun accountChanged(account: String?) {
        if (accepted?.account?.equals(account, ignoreCase = true) == false ||
            feedback.value?.account?.equals(account, ignoreCase = true) == false
        ) clear()
    }

    fun clear() {
        reset()
        mutableInvalidations.value += 1
    }

    private fun reset() {
        deadlineJob?.cancel()
        accepted = null
        wasPresented = false
        phase = Phase.Finished
        confirmationExpiresAtMillis = null
        waitUntilMillis = null
        mutableFeedback.value = null
        saved.remove<ArrayList<String>>(KEY_OFFER)
    }

    private fun nextId(): Long = (++sequence).also { saved[KEY_SEQUENCE] = it }

    private fun saveAcknowledgement() {
        val pending = feedback.value ?: accepted?.takeIf { phase != Phase.Finished }
        saved[KEY_OFFER] = pending?.let {
            // Append to the legacy nine fields; old settled records remain terminal on restore.
            arrayListOf(
                it.id.toString(), it.account, it.lastPage?.toString().orEmpty(),
                it.expiresAtMillis?.toString().orEmpty(), it.kind.name,
                it.refreshFailed.toString(), waitUntilMillis?.toString().orEmpty(),
                (phase != Phase.Waiting).toString(), wasPresented.toString(),
                phase.name, it.revision.toString(), it.offerOnly.toString(),
            )
        }
    }

    private fun restore() {
        val record = saved.get<ArrayList<String>>(KEY_OFFER) ?: return
        val ack = restoreAcknowledgement(record)
        if (ack == null) {
            saved.remove<ArrayList<String>>(KEY_OFFER)
            return
        }
        accepted = ack.takeIf {
            it.kind == TopicAcknowledgement.Kind.Published || it.kind == TopicAcknowledgement.Kind.Edited
        }
        waitUntilMillis = record.getOrNull(6)?.toLongOrNull()
        // Old records only distinguished waiting from terminal. Never reopen an old terminal result.
        phase = Phase.entries.firstOrNull { it.name == record.getOrNull(9) }
            ?: if (record.getOrNull(7) == "false") Phase.Waiting else Phase.Finished
        wasPresented = record.getOrNull(8)?.toBooleanStrictOrNull() ?: (ack.expiresAtMillis != null)
        when {
            phase == Phase.Waiting -> awaitDeadline(ack.id)
            // A process death loses the in-flight refresh and the old host, not an unshown offer.
            // A predicted offer was presented with that confirmation: it is restored as an offer.
            phase == Phase.Deadline && wasPresented && ack.lastPage == null -> Unit
            else -> mutableFeedback.value = ack.forRestoredPresentation()
        }
    }

    private fun TopicAcknowledgement.forRestoredPresentation() =
        if (wasPresented && lastPage != null) copy(offerOnly = true) else this

    private fun restoreAcknowledgement(record: ArrayList<String>): TopicAcknowledgement? {
        val id = record.getOrNull(0)?.toLongOrNull()
        val account = record.getOrNull(1)
        val page = record.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
        val deadline = record.getOrNull(3)?.toLongOrNull()
        val kind = TopicAcknowledgement.Kind.entries.firstOrNull { it.name == record.getOrNull(4) }
        return when {
            id == null || account == null || kind == null -> null
            deadline != null && deadline <= now() -> null
            else -> TopicAcknowledgement(
                id, account, kind, page,
                refreshFailed = record.getOrNull(5)?.toBooleanStrictOrNull() ?: false,
                expiresAtMillis = deadline,
                revision = record.getOrNull(10)?.toIntOrNull() ?: 0,
                offerOnly = record.getOrNull(11)?.toBooleanStrictOrNull() ?: false,
            )
        }
    }

    private companion object {
        const val KEY_SEQUENCE = "topic_ack_sequence"
        const val KEY_OFFER = "topic_ack_offer"
    }
}
