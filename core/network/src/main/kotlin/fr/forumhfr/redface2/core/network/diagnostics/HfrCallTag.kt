package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRequestQueued
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCatalogueTrace
import kotlin.coroutines.CoroutineContext
import okhttp3.Request

/** Request tag contains only measurements and a safe collector, never credentials or URL text. */
class HfrCallTag(
    val queuedNanos: Long,
    val fetch: FlagFetchTrace?,
    val catalogue: HfrCatalogueTrace? = null,
) {
    var executeNanos: Long? = null
        private set

    fun executing(nanos: Long = System.nanoTime()) { executeNanos = nanos }
}

fun Request.withHfrDiagnostics(context: CoroutineContext): Request = newBuilder()
    .tag(HfrCallTag::class.java, HfrCallTag(
        queuedNanos = context[HfrRequestQueued]?.consume() ?: System.nanoTime(),
        fetch = context[FlagFetchTrace],
        catalogue = context[HfrCatalogueTrace],
    ))
    .build()
