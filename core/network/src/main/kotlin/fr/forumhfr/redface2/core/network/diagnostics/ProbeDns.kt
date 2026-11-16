package fr.forumhfr.redface2.core.network.diagnostics

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Dns

/** One wave owns these workers; a stuck system resolver must never retain the probe's lease. */
internal class ProbeDns(
    private val delegate: Dns,
    parallelism: Int,
    private val deadlineNanos: Long,
) : Dns, Closeable {
    private val executor = Executors.newFixedThreadPool(parallelism) { task ->
        Thread(task, "NetworkProbeDns").apply { isDaemon = true }
    }
    private val pending = mutableSetOf<Future<List<InetAddress>>>()
    private var closed = false

    override fun lookup(hostname: String): List<InetAddress> {
        val future = submit(hostname)
        try {
            return future.get((deadlineNanos - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            throw DeadlineExceeded()
        } catch (_: CancellationException) {
            throw Cancelled()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Probe DNS interrupted", error)
        } catch (error: ExecutionException) {
            throw UnknownHostException("Probe DNS failed").apply { initCause(error) }
        } finally {
            future.cancel(true)
            synchronized(this) { pending.remove(future) }
        }
    }

    @Synchronized
    private fun submit(hostname: String): Future<List<InetAddress>> {
        if (closed) throw Cancelled()
        return executor.submit<List<InetAddress>> { delegate.lookup(hostname) }.also { pending.add(it) }
    }

    @Synchronized
    override fun close() {
        closed = true
        // Future cancellation wakes lookup() even when native DNS ignores thread interruption.
        pending.forEach { it.cancel(true) }
        executor.shutdownNow()
    }

    internal class DeadlineExceeded : IOException("Probe DNS deadline")
    internal class Cancelled : IOException("Probe DNS cancelled")
}
