package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize

/**
 * #175 — process-wide cache of measured intrinsic media sizes, keyed by image URL.
 *
 * The URL's native size is immutable, so we measure it once (via [measureIntrinsicMediaSize]) and
 * reuse it for every occurrence across posts/screens — the N copies of the same perso smiley do not
 * re-measure once the first result has landed. Builtin HFR smileys bypass measurement entirely and
 * use their known small size. Backed by a Compose `SnapshotStateMap` so a write (when a measurement
 * lands) triggers recomposition of the paragraphs reading that URL, which then rebuild their
 * placeholders at the final size.
 *
 * SUCCESSES ONLY (#960): measurement FAILURES — their TTL, their retry generations, their
 * clear-on-refresh protocol — live in the [MediaAttemptLedger], the single source of truth for
 * every media attempt (probe AND painter axes). The pre-#960 failure memoization this cache
 * carried (putFailure / failure epoch / clearFailures) is gone with it.
 *
 * Lives in `:core:ui` (no Hilt — the module has no DI) and is exposed via a process-wide singleton +
 * a CompositionLocal so tests can inject a pre-filled fake. Not persisted (Room/DataStore): the
 * Coil disk cache makes a cold-start re-measure cheap, and `PostContent` stays frozen.
 */
internal interface IntrinsicMediaSizeCache {
    /** Measured native size for [url], or `null` if not yet measured. */
    fun get(url: String): IntSize?

    fun putSuccess(url: String, size: IntSize)
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

    private val entries = mutableStateMapOf<String, IntSize>()
    private val insertionOrder = ArrayDeque<String>()
    private val lock = Any()

    override fun get(url: String): IntSize? = entries[url]

    override fun putSuccess(url: String, size: IntSize) {
        synchronized(lock) {
            if (!entries.containsKey(url)) insertionOrder.addLast(url)
            entries[url] = size
            while (insertionOrder.size > maxEntries) {
                val evicted = insertionOrder.removeFirst()
                entries.remove(evicted)
            }
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
 * Exposes the [IntrinsicMediaSizeCache] to the post renderer. Defaults to the process-wide singleton
 * so no wiring is required at the app entry point; tests override it with a pre-filled fake via
 * `CompositionLocalProvider` to assert measured sizing deterministically.
 */
internal val LocalIntrinsicMediaSizeCache = staticCompositionLocalOf<IntrinsicMediaSizeCache> {
    ProcessIntrinsicMediaSizeCache
}
