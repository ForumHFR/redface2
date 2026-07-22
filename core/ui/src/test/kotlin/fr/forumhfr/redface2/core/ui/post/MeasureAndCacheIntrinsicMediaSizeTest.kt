package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.test.FakeImageLoaderEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #249 follow-up / #960 — guards for the shared [measureAndCacheIntrinsicMediaSize] seam used by BOTH
 * the paragraph measure effect and the standalone `PostBlock.Image` effect. These pin the feeding
 * contract: measure once on a miss, never re-probe a known size or a fresh PROBE failure, settle
 * every outcome on the ledger, and never leave the axis in-flight after a cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class MeasureAndCacheIntrinsicMediaSizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loaderReturning(url: String, image: ColorImage): ImageLoader {
        val engine = FakeImageLoaderEngine.Builder().intercept(url, image).build()
        return ImageLoader.Builder(context).components { add(engine) }.build()
    }

    @Test
    fun `cold miss measures once, stores the native size and settles the probe axis`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/photo800x600.jpg"
        val loader = loaderReturning(url, ColorImage(width = 800, height = 600))

        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader)

        assertEquals(IntSize(800, 600), cache.get(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a URL already in the cache is not re-measured`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/photo.jpg"
        cache.putSuccess(url, IntSize(800, 600))
        // The fake loader would report a DIFFERENT size if a probe ran — proving a no-op if it doesn't.
        val loader = loaderReturning(url, ColorImage(width = 70, height = 50))

        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader)

        assertEquals("the pre-cached size must survive (no re-probe)", IntSize(800, 600), cache.get(url))
    }

    @Test
    fun `a fresh probe failure is not re-probed`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/dead.jpg"
        ledger.settleFailure(url, ledger.generationOf(url), MediaAttemptKind.PROBE, System.currentTimeMillis())
        // Would succeed if probed — so a non-null result here would mean the failure guard was ignored.
        val loader = loaderReturning(url, ColorImage(width = 70, height = 50))

        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader)

        assertNull("a fresh failure must short-circuit before probing", cache.get(url))
        assertTrue(ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()))
    }

    @Test
    fun `a dead url settles a probe failure on the ledger`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/dead.jpg"
        // Not intercepted → Coil error result, the production failure mode.
        val loader = ImageLoader.Builder(context)
            .components { add(FakeImageLoaderEngine.Builder().default(ColorImage(0)).build()) }
            .build()

        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ -> null }

        assertNull(cache.get(url))
        assertTrue(ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()))
    }

    @Test
    fun `a scoped retry re-opens the probe path (813 refresh contract)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/ghost.jpg"
        ledger.settleFailure(url, ledger.generationOf(url), MediaAttemptKind.PROBE, System.currentTimeMillis())
        // The outage recovered : the host now serves the image.
        val loader = loaderReturning(url, ColorImage(width = 320, height = 240))

        ledger.retryFailedUrls(setOf(url))
        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader)

        assertEquals(IntSize(320, 240), cache.get(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `an evicted geometry re-measures despite the terminal probe (Sol P2, O1)`() = runTest {
        // maxEntries=1: measuring u2 evicts u1's geometry while u1's probe axis stays Succeeded.
        // The §6 locked slot must survive eviction: the seam reopens the probe and re-deposits.
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 1)
        val ledger = MediaAttemptLedger()
        val u1 = "https://hfr/one.jpg"
        val u2 = "https://hfr/two.jpg"
        val loader = loaderReturning(u1, ColorImage(width = 1, height = 1))
        var u1Probes = 0

        measureAndCacheIntrinsicMediaSize(u1, cache, ledger, context, loader) { _, _, _ ->
            u1Probes++
            IntSize(320, 240)
        }
        measureAndCacheIntrinsicMediaSize(u2, cache, ledger, context, loader) { _, _, _ -> IntSize(70, 50) }
        assertNull("u1 was evicted by u2", cache.get(u1))
        assertTrue(ledger.hasSucceeded(u1, MediaAttemptKind.PROBE))

        measureAndCacheIntrinsicMediaSize(u1, cache, ledger, context, loader) { _, _, _ ->
            u1Probes++
            IntSize(320, 240)
        }
        assertEquals("the lost geometry must be re-measured", IntSize(320, 240), cache.get(u1))
        assertEquals(2, u1Probes)
        assertTrue(ledger.hasSucceeded(u1, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a G2 settlement during an in-flight probe keeps the first pair and the terminal axis`() = runTest {
        // Sol P2 — executable race pins: the probe is IN FLIGHT when the painter's G2 settlement
        // lands (deposit + terminal PROBE). Whatever the probe returns afterwards:
        //  - a DIVERGING success must not apply a second correction (§3 first-pair authority);
        //  - a failure must not demote the terminal success (monotonic, V4).
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/g2-race.jpg"
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1))
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun raceProbe(cache: IntrinsicMediaSizeCache, result: IntSize?) {
            val job = launch {
                measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                    probing.complete(Unit)
                    release.await()
                    result
                }
            }
            probing.await()
            // The painter's G2 settlement lands mid-probe (main thread in production).
            cache.putSuccessIfAbsent(url, IntSize(320, 240))
            ledger.settleSuccess(url, ledger.generationOf(url), MediaAttemptKind.PROBE)
            release.complete(Unit)
            job.join()
        }

        val cache = DefaultIntrinsicMediaSizeCache()
        raceProbe(cache, IntSize(999, 111)) // diverging probe success
        assertEquals("the FIRST pair (G2) keeps the authority", IntSize(320, 240), cache.get(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        assertFalse(
            "no failure may be recorded around the race",
            ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()),
        )
    }

    @Test
    fun `a probe failing after a mid-flight G2 settlement cannot demote it`() = runTest {
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/g2-race-fail.jpg"
        val cache = DefaultIntrinsicMediaSizeCache()
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1))
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val job = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                probing.complete(Unit)
                release.await()
                null // the probe FAILS after the G2 settlement below
            }
        }
        probing.await()
        cache.putSuccessIfAbsent(url, IntSize(320, 240))
        ledger.settleSuccess(url, ledger.generationOf(url), MediaAttemptKind.PROBE)
        release.complete(Unit)
        job.join()

        assertTrue("the terminal success survives (monotonic)", ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        assertFalse(ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()))
        assertEquals(IntSize(320, 240), cache.get(url))
    }

    @Test
    fun `a probe cancelled after a mid-flight G2 settlement does not roll the axis back`() = runTest {
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/g2-race-cancel.jpg"
        val cache = DefaultIntrinsicMediaSizeCache()
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1))
        val probing = CompletableDeferred<Unit>()

        val job = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                probing.complete(Unit)
                awaitCancellation()
            }
        }
        probing.await()
        cache.putSuccessIfAbsent(url, IntSize(320, 240))
        ledger.settleSuccess(url, ledger.generationOf(url), MediaAttemptKind.PROBE)
        job.cancelAndJoin()

        assertTrue(
            "the rollback must not reopen a terminally-succeeded axis",
            ledger.hasSucceeded(url, MediaAttemptKind.PROBE),
        )
        assertEquals(IntSize(320, 240), cache.get(url))
    }

    @Test
    fun `a failure from a probe that predates a retry is discarded (813 stale write)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/ghost.jpg"
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1)) // bypassed by the fake probes
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // Old measure effect : its probe is in flight when the user retries. Compose only cancels
        // it at the next recomposition — here it simply completes (with a failure) after the bump.
        val old = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                probing.complete(Unit)
                release.await()
                null
            }
        }
        probing.await()

        ledger.retryUrl(url) // the user's explicit retry, mid-probe: opens a new generation
        release.complete(Unit)
        old.join()

        assertFalse(
            "a failure produced by a probe older than the retry must be discarded",
            ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()),
        )
        // The path stays open : the retry probes and lands.
        measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ -> IntSize(320, 240) }
        assertEquals(IntSize(320, 240), cache.get(url))
    }

    @Test
    fun `a cancelled probe rolls back and a waiting caller takes over (813 race)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val ledger = MediaAttemptLedger()
        val url = "https://hfr/slow.jpg"
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1)) // bypassed by the fake probes
        val winnerProbing = CompletableDeferred<Unit>()

        // Winner: takes the reservation + in-flight ticket then hangs until cancelled — a
        // disposed measure effect mid-probe.
        val winner = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                winnerProbing.complete(Unit)
                awaitCancellation()
            }
        }
        winnerProbing.await()

        // Loser: arrives while the winner holds the ticket — must WAIT (not no-op), then take over.
        val loser = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, ledger, context, loader) { _, _, _ ->
                IntSize(320, 240)
            }
        }

        winner.cancelAndJoin()
        loser.join()

        assertEquals(
            "the waiting caller must re-probe after the cancelled winner rolled back",
            IntSize(320, 240),
            cache.get(url),
        )
        assertFalse(
            "a cancelled probe is not a dead host — no failure may be recorded",
            ledger.isFailedFresh(url, MediaAttemptKind.PROBE, System.currentTimeMillis()),
        )
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }
}
