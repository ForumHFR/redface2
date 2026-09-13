package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * #175 — process-wide, URL-keyed intrinsic sizing cache, observable by Compose snapshots.
 * Builtin smileys bypass measurement; perso smileys reuse their measured size across occurrences.
 * #960 moved failures, their TTL and retry generations into [MediaAttemptLedger] (successes only
 * here). #973 ([AMENDEMENT-v1.5-2]) made size + MIME one atomic, first-deposit cache entry.
 *
 * [AMENDEMENT-v1.6-10] — this bounded FIFO is now a memo for content: layout reads the ledger
 * exclusively, so eviction/replacement cannot change its geometry or reopen its probe. Content
 * writers mirror the ledger's merged metadata, including reliable null→known MIME enrichment.
 * Smileys retain their snapshot-observable sizing memo and historical FIFO repair path.
 * Lives in `:core:ui` without Hilt; singleton + CompositionLocal, no Room/DataStore persistence.
 * Nothing survives process death; the public Coil disk cache makes a cold re-measure cheap.
 */
internal interface IntrinsicMediaSizeCache {
    /** Memoized metadata, or null on a miss/eviction; never the authority for content layout. */
    fun get(url: String): IntrinsicMediaMetadata?

    fun putSuccess(url: String, metadata: IntrinsicMediaMetadata)

    /**
     * #960 P2 (§3/§6), #973 — atomic first-deposit operation, retained for the smiley memo.
     * v1.6-10 transfers content's first-pair authority to [MediaAttemptLedger.acceptGeometry];
     * content writers use [putSuccess] to memoize its accepted pair and possible MIME enrichment.
     */
    fun putSuccessIfAbsent(url: String, metadata: IntrinsicMediaMetadata): Boolean
}

/**
 * Default [IntrinsicMediaSizeCache]: a `SnapshotStateMap` bounded to [maxEntries] with FIFO
 * eviction (insertion order — native sizes are immutable and re-measure is cheap via the Coil disk
 * cache, so true LRU is overkill). Thread-safe: the snapshot system guards concurrent map access
 * (writes from the IO dispatcher, reads from the main thread); the auxiliary insertion-order queue
 * is guarded by [lock].
 */
internal class DefaultIntrinsicMediaSizeCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : IntrinsicMediaSizeCache {

    private val entries = mutableStateMapOf<String, IntrinsicMediaMetadata>()
    private val insertionOrder = ArrayDeque<String>()
    private val lock = Any()

    override fun get(url: String): IntrinsicMediaMetadata? = entries[url]

    override fun putSuccess(url: String, metadata: IntrinsicMediaMetadata) {
        synchronized(lock) {
            if (!entries.containsKey(url)) insertionOrder.addLast(url)
            entries[url] = metadata
            while (insertionOrder.size > maxEntries) {
                val evicted = insertionOrder.removeFirst()
                entries.remove(evicted)
            }
        }
    }

    override fun putSuccessIfAbsent(url: String, metadata: IntrinsicMediaMetadata): Boolean {
        synchronized(lock) {
            if (entries.containsKey(url)) return false
            putSuccess(url, metadata)
            return true
        }
    }

    internal companion object {
        const val DEFAULT_MAX_ENTRIES = 1024
    }
}

/**
 * Process-wide default cache instance. Survives recomposition and LazyColumn recycling (it lives
 * above the composition); NOT process death — the Coil disk cache makes a cold re-measure cheap.
 */
internal object ProcessIntrinsicMediaSizeCache :
    IntrinsicMediaSizeCache by DefaultIntrinsicMediaSizeCache()

/**
 * Exposes the memo to the post renderer, defaulting to the process-wide singleton. Tests may
 * pre-fill it for smiley sizing; warm content tests seed [LocalMediaAttemptLedger] (v1.6-10).
 */
internal val LocalIntrinsicMediaSizeCache = staticCompositionLocalOf<IntrinsicMediaSizeCache> {
    ProcessIntrinsicMediaSizeCache
}
