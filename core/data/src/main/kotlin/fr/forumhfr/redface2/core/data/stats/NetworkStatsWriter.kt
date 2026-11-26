package fr.forumhfr.redface2.core.data.stats

import android.util.Log
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsReport
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRepository
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsStart.Companion.SECONDS_PER_HOUR
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Unflushed measurements live in this process and are lost if Android kills it. */
@Singleton
class NetworkStatsWriter @Inject internal constructor(
    private val buffer: NetworkStatsBuffer,
    private val store: NetworkStatsStore,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : NetworkStatsRepository {
    private val started = AtomicBoolean()
    private val logged = AtomicBoolean()
    private val mutex = Mutex()
    private var purged = false
    private val pending = mutableMapOf<Long, NetworkStatsTotals>()

    fun start() {
        if (buffer.isEnabled && started.compareAndSet(false, true)) {
            scope.launch(ioDispatcher) {
                write(Unit) { ensurePurged() }
                launch {
                    while (true) {
                        delay(FLUSH_INTERVAL_MS)
                        requestFlush()
                    }
                }
                while (true) {
                    buffer.awaitFlushSignal()
                    write(Unit) { flush() }
                }
            }
        }
    }

    fun requestFlush() {
        buffer.requestFlush()
    }

    override suspend fun read(from: Instant, until: Instant): NetworkStatsReport = write(NetworkStatsReport()) {
        if (from < until) {
            flush()
            store.read(from.hour(), until.hour() + if (until.isHourStart()) 0 else 1)
        } else {
            NetworkStatsReport()
        }
    }

    override suspend fun reset(): Boolean = write(false) {
        store.reset()
        buffer.reset()
        pending.clear()
        true
    }

    private suspend fun ensurePurged() {
        if (!purged) {
            store.purge(clock.instant().hour() - RETENTION_HOURS)
            purged = true
        }
    }

    private suspend fun flush() {
        buffer.drain().forEach { (hour, totals) -> pending.merge(hour, totals, NetworkStatsTotals::plus) }
        ensurePurged()
        if (pending.isNotEmpty()) {
            store.merge(pending)
            pending.clear()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Statistics must degrade when any local storage step fails.
    private suspend fun <T> write(fallback: T, block: suspend () -> T): T =
        if (!buffer.isEnabled) fallback else withContext(ioDispatcher) {
            mutex.withLock {
                // Once a transaction commits, its in-memory batch must be retired even if the reader leaves.
                withContext(NonCancellable) {
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (logged.compareAndSet(false, true)) Log.w(TAG, "Network statistics storage unavailable")
                        fallback
                    }
                }
            }
        }

    private fun Instant.hour() = Math.floorDiv(epochSecond, SECONDS_PER_HOUR)

    private fun Instant.isHourStart() = nano == 0 && epochSecond % SECONDS_PER_HOUR == 0L

    private companion object {
        const val TAG = "NetworkStats"
        const val RETENTION_HOURS = 90L * 24
        const val FLUSH_INTERVAL_MS = 60_000L
    }
}
