package fr.forumhfr.redface2.core.domain.stats

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
class NetworkStatsBuffer @Inject constructor(private val process: NetworkStatsProcess) : NetworkStatsRecorder {
    private val generation = AtomicReference(Generation())
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    override val isEnabled: Boolean get() = process.isMain

    fun requestFlush() {
        if (isEnabled) flushSignal.trySend(Unit)
    }

    suspend fun awaitFlushSignal() {
        flushSignal.receive()
    }

    override fun started(start: NetworkStatsStart): NetworkStatsRecorder.CallRecording? =
        if (isEnabled) Recording(generation.get(), start) else null

    /** Only the single writer calls drain and reset. */
    suspend fun drain(): Map<Long, NetworkStatsTotals> {
        val batch = generation.get().active.getAndSet(Batch(::requestFlush))
        while (batch.writers.get() != 0) yield()
        return batch.snapshot()
    }

    fun reset() {
        generation.set(Generation())
    }

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
            NetworkDimensions(process.versionCode, target, start.role, start.visibility, trigger)
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

        private fun onInsert() {
            if (keys.incrementAndGet() == FLUSH_KEY_COUNT) signal()
        }

        fun snapshot(): Map<Long, NetworkStatsTotals> {
            val requestHours = requests.byHour()
            val noRequestHours = noRequests.byHour()
            val peakHours = peaks.byHour()
            return (requestHours.keys + noRequestHours.keys + peakHours.keys).associateWith { hour ->
                NetworkStatsTotals(
                    requestHours[hour].orEmpty(), noRequestHours[hour].orEmpty(), peakHours[hour].orEmpty(),
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
        const val SLOTS = 1024
        const val MAX_PROBES = 16
        const val FLUSH_KEY_COUNT = 256
        const val WINDOW_SLOTS = 4
        const val SECONDS_PER_MINUTE = 60L
    }
}
