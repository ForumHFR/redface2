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

/** ViewModel-owned acceptance and saved offer. Progress and snackbar lifetimes are independent. */
internal class TopicAcknowledgements(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var sequence = saved.get<Long>(KEY_SEQUENCE) ?: 0L
    private var accepted: TopicAcknowledgement? = null
    private var wasPresented = false
    private var settled = false
    private var deadlineJob: Job? = null
    private val mutableFeedback = MutableStateFlow<TopicAcknowledgement?>(restore())
    val feedback = mutableFeedback.asStateFlow()
    private val mutableInvalidations = MutableStateFlow(0)
    val invalidations = mutableInvalidations.asStateFlow()

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
        deadlineJob = scope.launch {
            delay(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            settle(ack.id, null)
        }
        return ack.id
    }

    fun refreshed(id: Long, lastPage: Int?) = settle(id, lastPage)

    private fun settle(id: Long, lastPage: Int?) {
        val ack = accepted?.takeIf { it.id == id && !settled } ?: return
        settled = true
        deadlineJob?.cancel()
        mutableFeedback.value = ack.copy(
            lastPage = lastPage.takeIf { ack.kind == TopicAcknowledgement.Kind.Published },
        )
        saveOffer()
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
        saveOffer()
    }

    fun deleted(account: String) {
        reset()
        mutableFeedback.value = TopicAcknowledgement(nextId(), account, TopicAcknowledgement.Kind.Deleted)
    }

    fun refreshWarning(account: String) {
        mutableFeedback.value = TopicAcknowledgement(nextId(), account, TopicAcknowledgement.Kind.RefreshWarning)
        saveOffer()
    }

    fun forPresentation(id: Long): TopicAcknowledgement? {
        val ack = feedback.value?.takeIf { it.id == id } ?: return null
        if (ack.expiresAtMillis?.let { it <= now() } == true) {
            dismiss(id)
            return null
        }
        return ack
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
        saveOffer()
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
        saveOffer()
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
        mutableFeedback.value = null
        saved.remove<ArrayList<String>>(KEY_OFFER)
    }

    private fun nextId(): Long = (++sequence).also { saved[KEY_SEQUENCE] = it }

    private fun saveOffer() {
        val offer = feedback.value?.takeIf { it.lastPage != null }
        saved[KEY_OFFER] = offer?.let {
            arrayListOf(
                it.id.toString(), it.account, it.lastPage.toString(),
                it.expiresAtMillis?.toString().orEmpty(), it.kind.name,
            )
        }
    }

    private fun restore(): TopicAcknowledgement? {
        val record = saved.get<ArrayList<String>>(KEY_OFFER) ?: return null
        val id = record.getOrNull(0)?.toLongOrNull() ?: return null
        val account = record.getOrNull(1) ?: return null
        val page = record.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val deadline = record.getOrNull(3)?.toLongOrNull()
        val kind = TopicAcknowledgement.Kind.entries.firstOrNull { it.name == record.getOrNull(4) } ?: return null
        if (deadline != null && deadline <= now()) {
            saved.remove<ArrayList<String>>(KEY_OFFER)
            return null
        }
        return TopicAcknowledgement(id, account, kind, page, expiresAtMillis = deadline)
    }

    private companion object {
        const val KEY_SEQUENCE = "topic_ack_sequence"
        const val KEY_OFFER = "topic_ack_offer"
    }
}
