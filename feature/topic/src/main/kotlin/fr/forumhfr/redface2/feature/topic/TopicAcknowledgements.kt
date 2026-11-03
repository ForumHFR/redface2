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
) {
    enum class Kind { Published, Edited, Deleted, RefreshWarning }
}

/** Save acceptance before consuming the relay, then keep it until presentation (or offer expiry). */
internal class TopicAcknowledgements(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var sequence = saved.get<Long>(KEY_SEQUENCE) ?: 0L
    private var accepted: TopicAcknowledgement? = null
    private var wasPresented = false
    private var settled = true
    private var waitUntilMillis: Long? = null
    private var deadlineJob: Job? = null
    private val mutableFeedback = MutableStateFlow<TopicAcknowledgement?>(null)
    val feedback = mutableFeedback.asStateFlow()
    private val mutableInvalidations = MutableStateFlow(0)
    val invalidations = mutableInvalidations.asStateFlow()

    init {
        restore()
    }

    fun accept(account: String, operation: PostSubmitOperation): Long {
        reset()
        val ack = TopicAcknowledgement(
            nextId(), account,
            if (operation == PostSubmitOperation.Edit) TopicAcknowledgement.Kind.Edited
            else TopicAcknowledgement.Kind.Published,
        )
        accepted = ack
        wasPresented = false
        settled = false
        waitUntilMillis = now() + TOPIC_SUBMIT_ACK_WAIT_MILLIS
        saveAcknowledgement()
        awaitDeadline(ack.id)
        return ack.id
    }

    private fun awaitDeadline(id: Long) {
        val deadline = waitUntilMillis ?: now()
        deadlineJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(0))
            settle(id, null)
        }
    }

    fun refreshed(id: Long, lastPage: Int?) = settle(id, lastPage)

    private fun settle(id: Long, lastPage: Int?) {
        val ack = accepted?.takeIf { it.id == id && !settled } ?: return
        settled = true
        deadlineJob?.cancel()
        mutableFeedback.value = ack.copy(
            lastPage = lastPage.takeIf { ack.kind == TopicAcknowledgement.Kind.Published },
        )
        saveAcknowledgement()
    }

    fun refreshFailed(id: Long) {
        val ack = accepted?.takeIf { it.id == id } ?: return
        deadlineJob?.cancel()
        settled = true
        mutableFeedback.value = if (wasPresented) {
            TopicAcknowledgement(nextId(), ack.account, TopicAcknowledgement.Kind.RefreshWarning)
        } else {
            ack.copy(refreshFailed = true)
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

    fun forPresentation(id: Long): TopicAcknowledgement? {
        val ack = feedback.value?.takeIf { it.id == id } ?: return null
        return if (ack.expiresAtMillis?.let { it <= now() } == true) {
            dismiss(id)
            null
        } else {
            ack
        }
    }

    /** Called only when this event actually owns the host mutex. Consuming never cancels display. */
    fun presented(id: Long, timeoutMillis: Long) {
        val ack = feedback.value?.takeIf { it.id == id } ?: return
        if (accepted?.id == id) wasPresented = true
        if (ack.lastPage == null) {
            mutableFeedback.value = null
        } else {
            val startedAt = now()
            val deadline = ack.expiresAtMillis ?: (startedAt + timeoutMillis.coerceAtMost(Long.MAX_VALUE - startedAt))
            mutableFeedback.value = ack.copy(expiresAtMillis = deadline)
            deadlineJob?.cancel()
            deadlineJob = scope.launch {
                delay((deadline - now()).coerceAtLeast(0))
                dismiss(id)
            }
        }
        saveAcknowledgement()
    }

    fun remainingMillis(id: Long): Long? = feedback.value
        ?.takeIf { it.id == id }?.expiresAtMillis?.let { (it - now()).coerceAtLeast(0) }

    fun takeOffer(id: Long, account: String): Int? {
        val ack = feedback.value?.takeIf {
            it.id == id && it.account.equals(account, ignoreCase = true) &&
                (it.expiresAtMillis?.let { deadline -> deadline > now() } == true)
        } ?: return null
        dismiss(id)
        return ack.lastPage
    }

    fun dismiss(id: Long) {
        if (feedback.value?.id != id) return
        mutableFeedback.value = null
        saveAcknowledgement()
    }

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
        settled = true
        waitUntilMillis = null
        mutableFeedback.value = null
        saved.remove<ArrayList<String>>(KEY_OFFER)
    }

    private fun nextId(): Long = (++sequence).also { saved[KEY_SEQUENCE] = it }

    private fun saveAcknowledgement() {
        val pending = feedback.value ?: accepted?.takeIf { !settled && !wasPresented }
        saved[KEY_OFFER] = pending?.let {
            arrayListOf(
                it.id.toString(), it.account, it.lastPage?.toString().orEmpty(),
                it.expiresAtMillis?.toString().orEmpty(), it.kind.name,
                it.refreshFailed.toString(), waitUntilMillis?.toString().orEmpty(),
                settled.toString(), wasPresented.toString(),
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
        settled = record.getOrNull(7)?.toBooleanStrictOrNull() ?: true
        wasPresented = record.getOrNull(8)?.toBooleanStrictOrNull() ?: (ack.expiresAtMillis != null)
        if (settled) mutableFeedback.value = ack else awaitDeadline(ack.id)
    }

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
            )
        }
    }

    private companion object {
        const val KEY_SEQUENCE = "topic_ack_sequence"
        const val KEY_OFFER = "topic_ack_offer"
    }
}
