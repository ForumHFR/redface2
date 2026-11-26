package fr.forumhfr.redface2.core.domain.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsStart.Companion.SECONDS_PER_HOUR
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield

/** Each drain detaches a batch. Producers never wait for the drainer or touch the database. */
@Singleton
class NetworkStatsBuffer @Inject constructor(
    private val process: NetworkStatsProcess,
    private val clock: Clock = Clock.systemUTC(),
    private val elapsed: DiagnosticsClock = DiagnosticsClock(System::nanoTime),
) : NetworkStatsRecorder, NetworkActionRecorder {
    private val generation = AtomicReference(Generation())
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val foreground = ForegroundTime()

    override val isEnabled: Boolean get() = process.isMain

    fun requestFlush() {
        if (isEnabled) flushSignal.trySend(Unit)
    }

    suspend fun awaitFlushSignal() {
        flushSignal.receive()
    }

    override fun started(start: NetworkStatsStart): NetworkStatsRecorder.CallRecording? =
        if (isEnabled) Recording(generation.get(), start) else null

    override fun record(action: NetworkAction, trigger: NetworkTrigger, count: Long) {
        if (isEnabled && count > 0) addAction(clock.instant().epochSecond, action, trigger, count)
    }

    fun onVisibilityChanged(visibility: AppVisibility) {
        if (isEnabled) foreground.update(visibility)
    }

    private fun addAction(epochSecond: Long, action: NetworkAction, trigger: NetworkTrigger, count: Long) {
        val hour = Math.floorDiv(epochSecond, SECONDS_PER_HOUR)
        val key = HourKey(hour, NetworkActionKey(process.versionCode, action, trigger))
        generation.get().update { it.actions.update(key, count) }
    }

    /** Only the single writer calls drain and reset. */
    suspend fun drain(): Map<Long, NetworkStatsTotals> {
        foreground.checkpoint()
        val batch = generation.get().active.getAndSet(Batch(::requestFlush))
        while (batch.writers.get() != 0) yield()
        return batch.snapshot()
    }

    fun reset() {
        foreground.reset()
    }

    /** Lifecycle and writer checkpoints share a monotonic cursor; OkHttp never takes this lock. */
    private inner class ForegroundTime {
        private var cursor: ForegroundCursor? = null
        private var remainderNanos = 0L

        @Synchronized
        fun update(visibility: AppVisibility) {
            if (visibility == AppVisibility.FOREGROUND) {
                if (cursor == null) {
                    val wall = clock.instant().epochSecond
                    cursor = ForegroundCursor(wall, elapsed.elapsedRealtimeNanos() - remainderNanos)
                    addAction(wall, NetworkAction.FOREGROUND_SESSION, NetworkTrigger.NONE, 1L)
                }
            } else {
                val now = elapsed.elapsedRealtimeNanos()
                checkpointAt(now)
                cursor?.let { remainderNanos = (now - it.elapsedNanos).coerceAtLeast(0L) }
                cursor = null
            }
        }

        @Synchronized
        fun checkpoint() = checkpointAt(elapsed.elapsedRealtimeNanos())

        private fun checkpointAt(now: Long) {
            val start = cursor ?: return
            var seconds = (now - start.elapsedNanos).coerceAtLeast(0L) / NANOS_PER_SECOND
            val elapsedSeconds = seconds
            var wall = start.wallSecond
            while (seconds > 0L) {
                val inHour = minOf(seconds, SECONDS_PER_HOUR - Math.floorMod(wall, SECONDS_PER_HOUR))
                addAction(wall, NetworkAction.FOREGROUND_SECONDS, NetworkTrigger.NONE, inHour)
                wall += inHour
                seconds -= inHour
            }
            cursor = ForegroundCursor(wall, start.elapsedNanos + elapsedSeconds * NANOS_PER_SECOND)
        }

        @Synchronized
        fun reset() {
            generation.set(Generation())
            remainderNanos = 0L
            cursor = cursor?.let { ForegroundCursor(clock.instant().epochSecond, elapsed.elapsedRealtimeNanos()) }
        }
    }

    private data class ForegroundCursor(val wallSecond: Long, val elapsedNanos: Long)

    private inner class Generation {
        val active = AtomicReference(Batch(::requestFlush))
        val peaks = Array(NetworkPeakGroup.entries.size) { PeakWindows() }

        fun update(block: (Batch) -> Unit) {
            while (true) {
                val batch = active.get()
                batch.writers.incrementAndGet()
                try {
                    if (batch === active.get()) {
                        block(batch)
                        break
                    }
                } finally {
                    batch.writers.decrementAndGet()
                }
            }
        }
    }

    private inner class Recording(
        private val owner: Generation,
        private val start: NetworkStatsStart,
    ) : NetworkStatsRecorder.CallRecording {
        override fun sent(
            target: NetworkTarget,
            peakGroup: NetworkPeakGroup?,
            trigger: NetworkTrigger,
        ): NetworkStatsRecorder.WireRecording {
            val dimensions = dimensions(target, trigger)
            val windows = peakGroup?.let { group ->
                owner.peaks[group.ordinal].also { counter ->
                    val peak = counter.sent(start)
                    owner.update {
                        it.peaks.update(HourKey(start.hourUtc, NetworkPeakKey(process.versionCode, group)), peak)
                    }
                }
            }
            return object : NetworkStatsRecorder.WireRecording {
                override fun finish(status: NetworkStatus, counts: NetworkRequestCounts) {
                    windows?.finished()
                    owner.update {
                        it.requests.update(HourKey(start.hourUtc, NetworkRequestKey(dimensions, status)), counts)
                    }
                }
            }
        }

        override fun noRequest(target: NetworkTarget, reason: NoRequestReason, trigger: NetworkTrigger) {
            val key = NoRequestKey(dimensions(target, trigger), reason)
            owner.update { it.noRequests.update(HourKey(start.hourUtc, key), 1L) }
        }

        private fun dimensions(target: NetworkTarget, trigger: NetworkTrigger) =
            NetworkDimensions(process.versionCode, target, start.role, start.visibility, trigger, start.network)
    }

    private class PeakWindows {
        private val inFlight = AtomicLong()
        private val minutes = AtomicReferenceArray<MinuteWindow>(WINDOW_SLOTS)

        fun sent(start: NetworkStatsStart): NetworkPeaks {
            val concurrent = inFlight.incrementAndGet()
            val minute = Math.floorDiv(start.epochSecond, SECONDS_PER_MINUTE)
            val second = Math.floorMod(start.epochSecond, SECONDS_PER_MINUTE).toInt()
            val window = getWindow(minute, start.monotonicNanos)
            return NetworkPeaks(window.seconds.incrementAndGet(second), window.total.incrementAndGet(), concurrent)
        }

        fun finished() {
            inFlight.decrementAndGet()
        }

        private fun getWindow(minute: Long, monotonicNanos: Long): MinuteWindow {
            val slot = Math.floorMod(minute, WINDOW_SLOTS.toLong()).toInt()
            var replacement: MinuteWindow? = null
            while (true) {
                val previous = minutes.get(slot)
                if (previous?.minute == minute) return previous
                val next = replacement ?: MinuteWindow(minute, monotonicNanos).also { replacement = it }
                val alreadyReplaced = previous != null && monotonicNanos - previous.createdNanos < 0
                if (alreadyReplaced || minutes.compareAndSet(slot, previous, next)) break
            }
            return checkNotNull(replacement)
        }

        private class MinuteWindow(val minute: Long, val createdNanos: Long) {
            val seconds = AtomicLongArray(SECONDS_PER_MINUTE.toInt())
            val total = AtomicLong()
        }
    }

    private data class HourKey<K>(val hour: Long, val key: K)

    private class Batch(private val signal: () -> Unit) {
        val writers = AtomicInteger()
        private val keys = AtomicInteger()
        val requests = Counters<NetworkRequestKey, NetworkRequestCounts>(::onInsert, signal, NetworkRequestCounts::plus)
        val noRequests = Counters<NoRequestKey, Long>(::onInsert, signal, Long::plus)
        val peaks = Counters<NetworkPeakKey, NetworkPeaks>(::onInsert, signal, NetworkPeaks::max)

        val actions = Counters<NetworkActionKey, Long>(::onInsert, signal, Long::plus)

        private fun onInsert() {
            if (keys.incrementAndGet() == FLUSH_KEY_COUNT) signal()
        }

        fun snapshot(): Map<Long, NetworkStatsTotals> {
            val requestHours = requests.byHour()
            val noRequestHours = noRequests.byHour()
            val peakHours = peaks.byHour()
            val actionHours = actions.byHour()
            return (requestHours.keys + noRequestHours.keys + peakHours.keys + actionHours.keys).associateWith { hour ->
                NetworkStatsTotals(
                    requestHours[hour].orEmpty(), noRequestHours[hour].orEmpty(), peakHours[hour].orEmpty(),
                    actionHours[hour].orEmpty(),
                )
            }
        }
    }

    /** Probe a bounded number of slots. Overflow is folded by the writer. */
    private class Counters<K, V : Any>(
        private val onInsert: () -> Unit,
        private val signal: () -> Unit,
        private val merge: (V, V) -> V,
    ) {
        private val slots = AtomicReferenceArray<Entry<K, V>>(SLOTS)
        private val overflow = ConcurrentLinkedQueue<Delta<K, V>>()
        private val spilled = AtomicBoolean()

        fun update(key: HourKey<K>, delta: V) {
            val hash = key.hashCode()
            var index = (hash xor (hash ushr 16)) and (SLOTS - 1)
            repeat(MAX_PROBES) {
                if (mergeAt(index, key, delta)) return
                index = (index + 1) and (SLOTS - 1)
            }
            overflow.add(Delta(key, delta))
            if (spilled.compareAndSet(false, true)) signal()
        }

        private fun mergeAt(index: Int, key: HourKey<K>, delta: V): Boolean {
            var entry = slots.get(index)
            if (entry == null) {
                if (slots.compareAndSet(index, null, Entry(key, AtomicReference(delta)))) {
                    onInsert()
                    return true
                }
                entry = slots.get(index)
            }
            val matches = entry?.key == key
            if (matches) checkNotNull(entry).value.updateAndGet { merge(it, delta) }
            return matches
        }

        fun byHour(): Map<Long, Map<K, V>> = buildMap<Long, MutableMap<K, V>> {
            for (index in 0 until SLOTS) {
                slots.get(index)?.let { entry ->
                    getOrPut(entry.key.hour, ::mutableMapOf)[entry.key.key] = entry.value.get()
                }
            }
            overflow.forEach { getOrPut(it.key.hour, ::mutableMapOf).merge(it.key.key, it.value, merge) }
        }

        private class Entry<K, V>(val key: HourKey<K>, val value: AtomicReference<V>)
        private class Delta<K, V>(val key: HourKey<K>, val value: V)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SLOTS = 1024
        const val MAX_PROBES = 16
        const val FLUSH_KEY_COUNT = 256
        const val WINDOW_SLOTS = 4
        const val SECONDS_PER_MINUTE = 60L
    }
}
