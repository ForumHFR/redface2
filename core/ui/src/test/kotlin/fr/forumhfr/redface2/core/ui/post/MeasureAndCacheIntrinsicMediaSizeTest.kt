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
 * #249 follow-up — guards for the shared [measureAndCacheIntrinsicMediaSize] seam now used by BOTH the
 * paragraph measure effect and the standalone `PostBlock.Image` effect. The bug it fixes was that
 * standalone images were never measured (their cache entry stayed null → no reserved box → no full-width
 * fit / no reserved loading space). These pin the cache-feeding contract: measure once on a miss, never
 * re-probe a known size or a fresh failure.
 */
@RunWith(RobolectricTestRunner::class)
class MeasureAndCacheIntrinsicMediaSizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loaderReturning(url: String, image: ColorImage): ImageLoader {
        val engine = FakeImageLoaderEngine.Builder().intercept(url, image).build()
        return ImageLoader.Builder(context).components { add(engine) }.build()
    }

    @Test
    fun `cold miss measures once and stores the native size`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/photo800x600.jpg"
        val loader = loaderReturning(url, ColorImage(width = 800, height = 600))

        measureAndCacheIntrinsicMediaSize(url, cache, context, loader)

        assertEquals(IntSize(800, 600), cache.get(url))
    }

    @Test
    fun `a URL already in the cache is not re-measured`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/photo.jpg"
        cache.putSuccess(url, IntSize(800, 600))
        // The fake loader would report a DIFFERENT size if a probe ran — proving a no-op if it doesn't.
        val loader = loaderReturning(url, ColorImage(width = 70, height = 50))

        measureAndCacheIntrinsicMediaSize(url, cache, context, loader)

        assertEquals("the pre-cached size must survive (no re-probe)", IntSize(800, 600), cache.get(url))
    }

    @Test
    fun `a fresh failure is not re-probed`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/dead.jpg"
        cache.putFailure(url, System.currentTimeMillis())
        // Would succeed if probed — so a non-null result here would mean the failure guard was ignored.
        val loader = loaderReturning(url, ColorImage(width = 70, height = 50))

        measureAndCacheIntrinsicMediaSize(url, cache, context, loader)

        assertNull("a fresh failure must short-circuit before probing", cache.get(url))
        assertTrue(cache.isFailureFresh(url, System.currentTimeMillis()))
    }

    @Test
    fun `clearFailures re-opens the probe path (813 refresh contract)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/ghost.jpg"
        cache.putFailure(url, System.currentTimeMillis())
        // The outage recovered : the host now serves the image.
        val loader = loaderReturning(url, ColorImage(width = 320, height = 240))

        cache.clearFailures()
        measureAndCacheIntrinsicMediaSize(url, cache, context, loader)

        assertEquals(IntSize(320, 240), cache.get(url))
    }

    @Test
    fun `a failure from a probe that predates clearFailures is discarded (813 stale write)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/ghost.jpg"
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1)) // bypassed by the fake probes
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // Old measure effect : its probe is in flight when the user refreshes. Compose only cancels
        // it at the next recomposition — here it simply completes (with a failure) after the clear.
        val old = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, context, loader) { _, _, _ ->
                probing.complete(Unit)
                release.await()
                null
            }
        }
        probing.await()

        cache.clearFailures() // the user's explicit refresh, mid-probe
        release.complete(Unit)
        old.join()

        assertFalse(
            "a failure produced by a probe older than the clear must be discarded",
            cache.isFailureFresh(url, System.currentTimeMillis()),
        )
        // The path stays open : the retry probes and lands.
        measureAndCacheIntrinsicMediaSize(url, cache, context, loader) { _, _, _ -> IntSize(320, 240) }
        assertEquals(IntSize(320, 240), cache.get(url))
    }

    @Test
    fun `a cancelled probe records nothing and a waiting caller takes over (813 race)`() = runTest {
        val cache = DefaultIntrinsicMediaSizeCache()
        val url = "https://hfr/slow.jpg"
        val loader = loaderReturning(url, ColorImage(width = 1, height = 1)) // bypassed by the fake probes
        val winnerProbing = CompletableDeferred<Unit>()

        // Winner: takes the in-flight ticket then hangs until cancelled — the #813 generation bump
        // cancelling the previous measure effect mid-probe.
        val winner = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, context, loader) { _, _, _ ->
                winnerProbing.complete(Unit)
                awaitCancellation()
            }
        }
        winnerProbing.await()

        // Loser: arrives while the winner holds the ticket — must WAIT (not no-op), then take over.
        val loser = launch {
            measureAndCacheIntrinsicMediaSize(url, cache, context, loader) { _, _, _ ->
                IntSize(320, 240)
            }
        }

        winner.cancelAndJoin()
        loser.join()

        assertEquals(
            "the waiting caller must re-probe after the cancelled winner released the ticket",
            IntSize(320, 240),
            cache.get(url),
        )
        assertFalse(
            "a cancelled probe is not a dead host — no failure may be recorded",
            cache.isFailureFresh(url, System.currentTimeMillis()),
        )
    }
}
