package fr.forumhfr.redface2.core.domain.diagnostics

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A shared public catalogue load retains safe transport data even without a refresh owner. */
class HfrCatalogueTrace : AbstractCoroutineContextElement(Key) {
    private val calls = mutableListOf<HfrCallSummary>()
    private var correlated = false

    @Synchronized fun call(summary: HfrCallSummary) { calls += summary }
    @Synchronized fun callSnapshot(): List<HfrCallSummary> = calls.toList()
    @Synchronized fun correlate() { correlated = true }
    @Synchronized fun hasRefresh(): Boolean = correlated

    companion object Key : CoroutineContext.Key<HfrCatalogueTrace>
}
