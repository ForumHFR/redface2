package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #960 P2 (Sol) — executable race pins of [PainterAttempt.onState], the painter-side G2 seam:
 * the geometry deposit runs on EVERY success (immutable-true pair, idempotent first-pair) while
 * the LEDGER settlements stay strictly grant- and generation-guarded.
 */
@RunWith(RobolectricTestRunner::class)
class PainterAttemptTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://images.example.org/attempt.jpg"

    private fun successState(width: Int, height: Int): AsyncImagePainter.State.Success =
        AsyncImagePainter.State.Success(
            painter = ColorPainter(Color.Red),
            result = SuccessResult(
                image = ColorImage(0xFF1565C0.toInt(), width = width, height = height),
                request = ImageRequest.Builder(context).data(url).build(),
            ),
        )

    @Test
    fun `a terminal painter's re-render redeposits an evicted geometry`() {
        // Sol P2 blocker: the FIFO cache evicted the url's geometry while both axes are terminal.
        // A re-rendered terminal painter is NOT granted — its success must still heal the cache
        // (and re-settle the probe axis reopened by the measurer's eviction repair).
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val gen = ledger.generationOf(url)
        ledger.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        ledger.settleSuccess(url, gen, MediaAttemptKind.PAINTER)

        val rerendered = PainterAttempt(ledger, cache, url, gen) // never granted
        rerendered.onState(successState(320, 240))

        assertEquals("the geometry must be redeposited", IntSize(320, 240), cache.get(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `the re-decode callback never applies a second correction`() {
        // §7: once the box is fixed, the re-decoded painter fires its own success callback —
        // with the decoded (possibly divergent) dimensions. First-pair authority must hold.
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val attempt = PainterAttempt(ledger, cache, url, ledger.generationOf(url))
        attempt.reserveIfUntried()
        attempt.onState(successState(800, 600)) // first decode fixes the pair

        attempt.onState(successState(320, 240)) // the §7 re-decode's callback

        assertEquals("the FIRST pair keeps the authority", IntSize(800, 600), cache.get(url))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a stale-generation success deposits the immutable geometry but settles nothing`() {
        // The user retried while the painter was in flight: the generation moved on. The late
        // success's LEDGER settlements are discarded (V5) — but the geometry deposit stays (the
        // native pair is immutable-true, and §6 wants the slot to survive generations anyway).
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val staleGen = ledger.generationOf(url)
        val attempt = PainterAttempt(ledger, cache, url, staleGen)
        attempt.reserveIfUntried()
        ledger.retryUrl(url) // generation moves on mid-flight

        attempt.onState(successState(320, 240))

        assertEquals("the immutable geometry stays", IntSize(320, 240), cache.get(url))
        assertFalse("the stale painter settlement is discarded", ledger.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertFalse("the stale probe settlement is discarded", ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        val fresh = ledger.generationOf(url)
        assertTrue(
            "the fresh generation's painter axis stays reservable",
            ledger.tryReserve(url, fresh, MediaAttemptKind.PAINTER),
        )
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
