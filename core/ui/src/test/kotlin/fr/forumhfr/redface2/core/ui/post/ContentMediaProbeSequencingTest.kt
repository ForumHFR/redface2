package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ContentMediaProbeSequencingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = ImageLoader.Builder(context).build()
    private val ledger = MediaAttemptLedger()
    private val cache = DefaultIntrinsicMediaSizeCache()
    private val url = "https://images.example.org/slow-content"
    private val metadata = IntrinsicMediaMetadata(IntSize(800, 600), "image/gif")
    private val caps = ContentMediaConstraints(350, 900f)

    private fun target(): IntSize? = contentMediaDecodeTarget(
        ledger.geometryOf(url)?.size, ledger.isSettled(url, MediaAttemptKind.PROBE),
        PostMediaDiskCachePolicy.ENABLED, caps,
    )

    @Test
    fun `probe success opens measured painter and failure opens rectangular G2`() = runTest {
        val release = CompletableDeferred<Unit>()
        val job = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                release.await()
                metadata
            }
        }
        runCurrent()
        assertNull(target())
        release.complete(Unit)
        job.join()
        assertEquals(decodeSizePx(350, metadata.size), target())
        assertEquals(metadata.size, ledger.geometryOf(url)?.size)
    }

    @Test
    fun `probe failure opens G2 after the execution returns`() = runTest {
        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ -> null }
        assertEquals(IntSize(512, 1024), target())
        assertTrue(ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()))
    }

    @Test
    fun `dedicated deadline is 30000 ms and waits for effective cancellation before G2`() = runTest {
        val finishing = CompletableDeferred<Unit>()
        val terminate = CompletableDeferred<Unit>()
        val job = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finishing.complete(Unit)
                        terminate.await()
                    }
                }
            }
        }
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertFalse(finishing.isCompleted)
        assertNull(target())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(finishing.isCompleted)
        assertFalse(job.isCompleted)
        assertNull("G2 must wait for actual probe termination", target())
        terminate.complete(Unit)
        job.join()
        assertEquals(IntSize(512, 1024), target())
        assertTrue(ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()))
        assertFalse(ledger.isUntried(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `owner cancellation is rollback and never a timeout failure`() = runTest {
        val job = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ -> awaitCancellation() }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(ledger.isUntried(url, MediaAttemptKind.PROBE))
        assertFalse(ledger.isSettled(url, MediaAttemptKind.PROBE))
        assertNull(target())
    }

    @Test
    fun `success just before deadline wins and a success swallowed after cancellation deposits nothing`() = runTest {
        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
            delay(29_999)
            metadata
        }
        assertEquals(metadata.size, ledger.geometryOf(url)?.size)
        val lateUrl = "$url/late"
        measureAndCacheIntrinsicMediaSize(lateUrl, cache, ledger, context, loader) { _, _, _ ->
            runCatching { delay(30_000) }
            metadata
        }
        assertNull(ledger.geometryOf(lateUrl))
        assertNull(cache.get(lateUrl))
        assertTrue(ledger.isFailedFresh(lateUrl, MediaAttemptKind.PROBE, System.currentTimeMillis()))
    }

    @Test
    fun `private content never executes a probe and perso smileys retain their probe`() = runTest {
        var probes = 0
        measureAndCacheIntrinsicMediaSize(
            url, cache, ledger, context, loader, PostMediaDiskCachePolicy.DISABLED,
        ) { _, _, _ -> probes++; metadata }
        assertEquals(0, probes)
        assertNull(ledger.geometryOf(url))
        assertTrue(ledger.isUntried(url, MediaAttemptKind.PROBE))
        measureAndCacheIntrinsicMediaSize(
            url, cache, ledger, context, loader, PostMediaDiskCachePolicy.DISABLED, isContentMedia = false,
        ) { _, _, _ -> probes++; metadata }
        assertEquals(1, probes)
        assertEquals(metadata, cache.get(url))
    }

    @Test
    fun `stale probe success cannot populate or enrich geometry after a retry`() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                release.await()
                metadata
            }
        }
        runCurrent()
        ledger.retryUrl(url)
        release.complete(Unit)
        old.join()
        assertNull(ledger.geometryOf(url))
        assertNull(cache.get(url))
        assertTrue(ledger.isUntried(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `stale timeout cannot fail the new generation`() = runTest {
        val old = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ -> awaitCancellation() }
        }
        runCurrent()
        ledger.retryUrl(url)
        advanceTimeBy(30_000)
        old.join()
        assertTrue(ledger.isUntried(url, MediaAttemptKind.PROBE))
        assertFalse(ledger.isSettled(url, MediaAttemptKind.PROBE))
        assertNull(cache.get(url))
    }
}
