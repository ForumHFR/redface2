package fr.forumhfr.redface2.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.feature.topic.TopicSubmitResult

/** Retain the owner of this editor entry across recreation and subsequent account changes. */
@Composable
internal fun rememberSubmitAccount(account: String?): String? {
    var owner by rememberSaveable { mutableStateOf(account) }
    if (owner == null && account != null) owner = account
    return owner
}

internal data class TopicPendingSubmit(val cat: Int, val post: Int, val result: TopicSubmitResult) {
    fun matches(cat: Int, post: Int): Boolean = this.cat == cat && this.post == post
}

/** Single saveable slot, published before pop and consumed only by matching event identity. */
internal class TopicSubmitRelay(private var sequence: Long = 0, pending: TopicPendingSubmit? = null) {
    var pending by mutableStateOf(pending)
        private set

    @Suppress("LongParameterList") // One serialized editor outcome, including its account and operation.
    fun publish(
        cat: Int,
        post: Int,
        targetPage: Int?,
        scrollTo: Int?,
        quotedNumreponses: List<Int>,
        operation: PostSubmitOperation,
        account: String,
    ) {
        pending = TopicPendingSubmit(
            cat, post,
            TopicSubmitResult(++sequence, targetPage, scrollTo, quotedNumreponses, operation, account),
        )
    }

    fun consume(id: Long) {
        if (pending?.result?.eventId == id) pending = null
    }

    fun clear() { pending = null }

    fun save(): List<Any> {
        val event = pending ?: return listOf(sequence)
        return listOf(
            sequence, event.cat, event.post, event.result.eventId,
            event.result.targetPage ?: 0, event.result.scrollTo ?: 0,
            ArrayList(event.result.quotedNumreponses), event.result.operation.name, event.result.account,
        )
    }

    companion object {
        val Saver = listSaver<TopicSubmitRelay, Any>(save = { it.save() }, restore = { restore(it) })

        fun restore(values: List<Any>): TopicSubmitRelay {
            val sequence = values[0] as Long
            if (values.size == 1) return TopicSubmitRelay(sequence)
            val result = TopicSubmitResult(
                eventId = values[3] as Long,
                targetPage = (values[4] as Int).takeIf { it > 0 },
                scrollTo = (values[5] as Int).takeIf { it > 0 },
                quotedNumreponses = (values[6] as List<*>).filterIsInstance<Int>(),
                operation = PostSubmitOperation.valueOf(values[7] as String),
                account = values[8] as String,
            )
            return TopicSubmitRelay(sequence, TopicPendingSubmit(values[1] as Int, values[2] as Int, result))
        }
    }
}
