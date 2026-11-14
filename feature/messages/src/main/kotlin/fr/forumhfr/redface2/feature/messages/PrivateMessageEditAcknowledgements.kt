package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Edit success survives refresh failure. Only unpresented confirmations resume after recreation. */
internal class PrivateMessageEditAcknowledgements(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableReady = MutableStateFlow<Long?>(null)
    val ready = mutableReady.asStateFlow()
    private var deadline: Job? = null

    fun bind(account: String) {
        val owner = saved.get<String>(OWNER)
        if (owner != null && owner != account) clear()
        val id = saved.get<Long>(ID) ?: return
        if (saved.get<Boolean>(PRESENTED) != true) await(id)
    }

    fun accept(account: String, id: Long) {
        clear()
        saved[OWNER] = account
        saved[ID] = id
        saved[DEADLINE] = now() + WAIT_MILLIS
        saved[PRESENTED] = false
        await(id)
    }

    fun refreshed(id: Long) {
        if (saved.get<Long>(ID) != id || saved.get<Boolean>(PRESENTED) == true) return
        deadline?.cancel()
        mutableReady.value = id
    }

    fun presented(id: Long) {
        if (mutableReady.value != id) return
        saved[PRESENTED] = true
        mutableReady.value = null
    }

    fun clear() {
        deadline?.cancel()
        mutableReady.value = null
        listOf(OWNER, ID, DEADLINE, PRESENTED).forEach { saved.remove<Any>(it) }
    }

    private fun await(id: Long) {
        deadline?.cancel()
        deadline = scope.launch {
            delay(((saved.get<Long>(DEADLINE) ?: now()) - now()).coerceAtLeast(0))
            refreshed(id)
        }
    }

    private companion object {
        const val OWNER = "mpEditAck.owner"
        const val ID = "mpEditAck.id"
        const val DEADLINE = "mpEditAck.deadline"
        const val PRESENTED = "mpEditAck.presented"
        const val WAIT_MILLIS = 1_000L
    }
}
