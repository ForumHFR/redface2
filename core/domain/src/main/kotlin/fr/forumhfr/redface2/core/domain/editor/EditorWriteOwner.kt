package fr.forumhfr.redface2.core.domain.editor

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** ED-162/163/170: carry the attempt's owner and emission witness through preparation and IO hops. */
class EditorWriteOwner(val owner: String?) : AbstractCoroutineContextElement(Key) {
    private enum class Emission { BEFORE, POSSIBLE, UNOBSERVED, CANCELLED_BEFORE }
    private val emission = AtomicReference(Emission.BEFORE)

    /** A missing witness is never proof; this instance covers local preparation and its POST. */
    val provesNoEmission: Boolean get() = when (emission.get()) {
        Emission.BEFORE, Emission.CANCELLED_BEFORE -> true
        else -> false
    }

    /** Only the POST network interceptor advances this, after the account guard. Never reset it. */
    fun markEmissionPossible() {
        if (!emission.compareAndSet(Emission.BEFORE, Emission.POSSIBLE) &&
            emission.get() == Emission.CANCELLED_BEFORE
        ) {
            throw IOException("Editor attempt cancelled before HTTP emission")
        }
    }

    /** A client without the network guard cannot supply a proof of non-emission. */
    fun emissionUnobserved() {
        if (!emission.compareAndSet(Emission.BEFORE, Emission.UNOBSERVED) &&
            emission.get() == Emission.CANCELLED_BEFORE
        ) {
            throw IOException("Editor attempt cancelled before unobserved transport")
        }
    }

    /** Seal against a network thread reaching the boundary after cancellation has returned. */
    fun cancelBeforeEmission(): Boolean =
        emission.compareAndSet(Emission.BEFORE, Emission.CANCELLED_BEFORE) ||
            emission.get() == Emission.CANCELLED_BEFORE

    @Volatile var rejectedBeforeEmission: Boolean = false
        private set

    /** Remember a certain refusal even if cancellation wins the HTTP continuation's result. */
    fun rejectBeforeEmission(): Nothing {
        rejectedBeforeEmission = true
        throw EditorAccountChangedBeforeSendException()
    }

    companion object Key : CoroutineContext.Key<EditorWriteOwner>
}

/** Only the transport guard may report this certain failure: no HTTP request has been emitted. */
class EditorAccountChangedBeforeSendException : IOException("Editor account changed before HTTP emission")
