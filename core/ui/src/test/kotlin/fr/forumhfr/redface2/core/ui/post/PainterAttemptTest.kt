package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.compose.AsyncImagePainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #960 P2 (Sol) — executable race pins of [PainterAttempt.onState], the painter-side G2 seam:
 * geometry and outcome writes are generation-guarded; the ledger owns the first valid pair.
 */
@RunWith(RobolectricTestRunner::class)
class PainterAttemptTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://images.example.org/attempt.jpg"

    private fun errorState() = AsyncImagePainter.State.Error(
        painter = null,
        result = ErrorResult(
            image = null,
            request = ImageRequest.Builder(context).data(url).build(),
            throwable = IllegalStateException("evicted bitmap, offline host"),
        ),
    )

    @Test
    fun `E1 terminal cache miss exposes shared failure until a manual retry`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val generation = ledger.generationOf(url)
        ledger.settleSuccess(url, generation, MediaAttemptKind.PAINTER)
        val occurrence = PainterAttempt(ledger, cache, url, generation)
        occurrence.reserveIfUntried() // denied: the earlier success is terminal
        assertTrue(occurrence.renderPainter)

        occurrence.onState(errorState())

        assertTrue(occurrence.failedFresh)
        assertTrue(PainterAttempt(ledger, cache, url, generation).failedFresh)
        assertFalse(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        occurrence.reserveIfUntried()
        assertEquals(generation, ledger.generationOf(url))
        assertFalse(ledger.tryReserve(url, generation, MediaAttemptKind.PAINTER))

        ledger.retryFailedUrls(setOf(url))
        val retried = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        retried.reserveIfUntried()
        assertEquals(generation + 1, ledger.generationOf(url))
        assertFalse(retried.failedFresh)
        assertTrue(retried.renderPainter)
    }

    @Test
    fun `E1 a later painter error preserves the successful geometry and probe`() {
        // E1 originally covered a §7 re-decode failure; v1.6-10 retains the post-success error gate.
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val attempt = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        attempt.reserveIfUntried()
        attempt.onState(successState(320, 240))

        attempt.onState(errorState())

        assertTrue(attempt.failedFresh)
        ledger.retryUrl(url)
        assertEquals(IntSize(320, 240), cache.get(url)?.size)
        assertEquals(IntSize(320, 240), ledger.geometryOf(url)?.size)
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        assertTrue(ledger.tryReserve(url, ledger.generationOf(url), MediaAttemptKind.PAINTER))
    }

    @Test
    fun `E1 a stale terminal occurrence cannot fail the new generation`() {
        val ledger = MediaAttemptLedger()
        val generation = ledger.generationOf(url)
        ledger.settleSuccess(url, generation, MediaAttemptKind.PAINTER)
        val stale = PainterAttempt(ledger, DefaultIntrinsicMediaSizeCache(), url, generation)
        ledger.retryUrl(url)

        stale.onState(errorState())

        assertFalse(stale.failedFresh)
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `a stale generation callback clears the loading announcement`() {
        val ledger = MediaAttemptLedger()
        val generation = ledger.generationOf(url)
        val stale = PainterAttempt(ledger, DefaultIntrinsicMediaSizeCache(), url, generation)
        assertTrue(stale.loading)
        ledger.retryUrl(url)

        stale.onState(AsyncImagePainter.State.Loading(ColorPainter(Color.Red)))

        assertFalse(stale.loading)
    }

    @Test
    fun `E1 an ungranted cache success without geometry leaves the ledger unchanged`() {
        val ledger = MediaAttemptLedger()
        val generation = ledger.generationOf(url)
        ledger.settleSuccess(url, generation, MediaAttemptKind.PAINTER)
        val occurrence = PainterAttempt(ledger, DefaultIntrinsicMediaSizeCache(), url, generation)

        occurrence.onState(successState(-1, -1))

        assertEquals(generation, ledger.generationOf(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertTrue(ledger.isUntried(url, MediaAttemptKind.PROBE))
        assertFalse(occurrence.failedFresh)
    }

    private fun successState(width: Int, height: Int): AsyncImagePainter.State.Success =
        AsyncImagePainter.State.Success(
            painter = ColorPainter(Color.Red),
            result = SuccessResult(
                image = ColorImage(0xFF1565C0.toInt(), width = width, height = height),
                request = ImageRequest.Builder(context).data(url).build(),
            ),
        )

    @Test
    fun `a remounted terminal painter memoizes the preserved authority after eviction`() {
        // #960 P2 used to repair lost geometry. v1.6-10 retains it in the ledger, so a remount's
        // ungranted success may restore the memo but cannot replace dimensions or reopen PROBE.
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 1)
        val gen = ledger.generationOf(url)
        val first = IntrinsicMediaMetadata(IntSize(800, 600), "image/gif")
        ledger.acceptGeometry(url, gen, first, MediaAttemptKind.PROBE, cache)
        ledger.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        ledger.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        cache.putSuccess("evictor", IntrinsicMediaMetadata(IntSize(1, 1), null))
        assertNull(cache.get(url))
        assertEquals(first.size, ledger.geometryOf(url)?.size)
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))

        val rerendered = PainterAttempt(ledger, cache, url, gen) // never granted
        rerendered.onState(successState(320, 240))

        assertEquals(first, cache.get(url))
        assertEquals(MediaGeometry(first.size, MediaAttemptKind.PROBE, first.mimeType), ledger.geometryOf(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a later divergent callback never applies a second correction`() {
        // #959 §7 formerly re-decoded cold→measured. v1.6-10 removes that request, but any later
        // success callback must still preserve the first pair (e.g. animation state delivery).
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val attempt = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        attempt.reserveIfUntried()
        attempt.onState(successState(800, 600)) // first decode fixes the pair

        attempt.onState(successState(320, 240))

        assertEquals("the FIRST pair keeps the authority", IntSize(800, 600), cache.get(url)?.size)
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a painter success never updates the probe's mime (no late reclassification)`() {
        // #973 ([AMENDEMENT-v1.5-2]) — the probe fixed (size, image/gif) atomically; the painter's
        // later success callback must leave the metadata UNTOUCHED, mime included: « AUCUN
        // reclassement tardif après fixation de la boîte ».
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val probed = IntrinsicMediaMetadata(IntSize(400, 300), "image/gif")
        ledger.acceptGeometry(url, 0, probed, MediaAttemptKind.PROBE, cache)
        val attempt = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        attempt.reserveIfUntried()

        attempt.onState(successState(400, 300))

        assertEquals("the probe's atomic metadata must survive the painter", probed, cache.get(url))
    }

    @Test
    fun `a stale-generation success deposits nothing and preserves already fixed geometry`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val attempt = PainterAttempt(ledger, cache, url, 0)
        attempt.reserveIfUntried()
        ledger.retryUrl(url)
        attempt.onState(successState(320, 240))
        assertNull(cache.get(url))
        assertNull(ledger.geometryOf(url))
        assertFalse(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertFalse(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        val current = IntrinsicMediaMetadata(IntSize(800, 600), "image/gif")
        ledger.acceptGeometry(url, 1, current, MediaAttemptKind.PROBE, cache)
        attempt.onState(successState(999, 111))
        assertEquals(current, cache.get(url))
        assertEquals(current.size, ledger.geometryOf(url)?.size)
        assertEquals(MediaAttemptKind.PROBE, ledger.geometryOf(url)?.provenance)
        assertTrue(ledger.tryReserve(url, 1, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `a geometry-less success settles the painter but leaves the probe alone`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val attempt = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        attempt.reserveIfUntried()

        attempt.onState(successState(-1, -1))

        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertFalse("no G2 without usable dimensions", ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        assertEquals(null, cache.get(url))
    }
}
