package fr.forumhfr.redface2.core.domain.diagnostics

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Diagnostic intent only; never changes request execution, authentication or read policies. */
class HfrCallIntent(val kind: Kind) : AbstractCoroutineContextElement(Key) {
    enum class Kind(val label: String) { MP_BADGE("mp-badge") }
    companion object Key : CoroutineContext.Key<HfrCallIntent>
}
