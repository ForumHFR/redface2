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
 * Failures are memoized too (with a TTL): a dead host / 404 must not be re-fetched on every
 * recomposition or LazyColumn re-entry — without this the cold-cache placeholder path would flood
 * the network. After [failureTtlMillis] a failed URL may be retried (a transient outage recovers).
 *
 * Lives in `:core:ui` (no Hilt — the module has no DI) and is exposed via a process-wide singleton +
 * a CompositionLocal so tests can inject a pre-filled fake. Not persisted (Room/DataStore): the
 * Coil disk cache makes a cold-start re-measure cheap, and `PostContent` stays frozen.
 */
internal interface IntrinsicMediaSizeCache {
    /** Measured native size for [url], or `null` if not yet measured (or only a failure is recorded). */
    fun get(url: String): IntSize?

    /** `true` when a measurement failure for [url] is still within [failureTtlMillis] of [nowMillis]. */
    fun isFailureFresh(url: String, nowMillis: Long): Boolean

    fun putSuccess(url: String, size: IntSize)

    fun putFailure(url: String, nowMillis: Long)

    /**
     * #813 — drop every memoized failure (successes are kept: native sizes are immutable).
     * Called on an explicit user refresh so a transient outage does not leave ghost images
     * pinned to the cold fallback box until the TTL happens to be re-consulted.
     */
    fun clearFailures()
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
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MILLIS,
) : IntrinsicMediaSizeCache {

    private sealed interface Entry {
        data class Success(val size: IntSize) : Entry
        data class Failure(val atMillis: Long) : Entry
    }

    private val entries = mutableStateMapOf<String, Entry>()
    private val insertionOrder = ArrayDeque<String>()
    private val lock = Any()

    override fun get(url: String): IntSize? = (entries[url] as? Entry.Success)?.size

    override fun isFailureFresh(url: String, nowMillis: Long): Boolean {
        val failure = entries[url] as? Entry.Failure ?: return false
        return nowMillis - failure.atMillis < failureTtlMillis
    }

    override fun putSuccess(url: String, size: IntSize) = put(url, Entry.Success(size))

    override fun putFailure(url: String, nowMillis: Long) = put(url, Entry.Failure(nowMillis))

    override fun clearFailures() {
        synchronized(lock) {
            val failed = entries.filterValues { it is Entry.Failure }.keys
            failed.forEach { url ->
                entries.remove(url)
                insertionOrder.remove(url)
            }
        }
    }

    private fun put(url: String, entry: Entry) {
        synchronized(lock) {
            if (!entries.containsKey(url)) insertionOrder.addLast(url)
            entries[url] = entry
            while (insertionOrder.size > maxEntries) {
                val evicted = insertionOrder.removeFirst()
                entries.remove(evicted)
            }
        }
    }

    internal companion object {
        const val DEFAULT_MAX_ENTRIES = 1024
        const val DEFAULT_FAILURE_TTL_MILLIS = 60_000L
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

/**
 * #813 — public seam for the hosting screens (:feature modules cannot see the internal cache):
 * drop the memoized measurement failures so the next measure pass re-probes them. Call it on an
 * explicit user refresh, BEFORE bumping the media-refresh generation passed to [PostRenderer] —
 * clearing after the bump would let the relaunched effect re-read a still-fresh failure.
 */
fun clearPostMediaMeasurementFailures() {
    ProcessIntrinsicMediaSizeCache.clearFailures()
}
