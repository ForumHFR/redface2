package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.FlagType
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A shared public catalogue load retains safe transport data even without a refresh owner. */
class HfrCatalogueTrace : AbstractCoroutineContextElement(Key) {
    private val calls = mutableListOf<HfrCallSummary>()
    private val publications = mutableMapOf<HfrCallPublication, CatalogueOwner>()

    @Synchronized fun call(summary: HfrCallSummary): Boolean {
        calls += summary
        publications.keys.removeAll { !it.isActive() }
        var deferred = false
        publications.forEach { (publication, owner) ->
            deferred = publication.call(owner.summary(summary)) || deferred
        }
        return deferred
    }
    @Synchronized fun callSnapshot(): List<HfrCallSummary> = calls.toList()
    @Synchronized internal fun publishTo(publication: HfrCallPublication, refreshId: Long, type: FlagType) {
        publications.keys.removeAll { !it.isActive() }
        if (!publication.isActive() || publication in publications) return
        val owner = CatalogueOwner(refreshId, type)
        publications[publication] = owner
        calls.forEach { publication.call(owner.summary(it)) }
    }

    private class CatalogueOwner(private val refreshId: Long, private val type: FlagType) {
        fun summary(call: HfrCallSummary): HfrCallSummary =
            call.copy(refreshId = refreshId, flagType = type, phase = FlagRefreshPhase.CATALOGUE)
    }

    companion object Key : CoroutineContext.Key<HfrCatalogueTrace>
}
