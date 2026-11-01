package fr.forumhfr.redface2.core.domain.editor

import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** ED-170: carry the durable attempt's owner through local preparation and dispatcher hops. */
class EditorWriteOwner(val owner: String?) : AbstractCoroutineContextElement(Key) {
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
