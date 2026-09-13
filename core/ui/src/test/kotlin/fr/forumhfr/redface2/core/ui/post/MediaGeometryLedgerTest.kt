package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** v1.6-10: process authority, independent of the FIFO memo and attempt generations. */
@RunWith(RobolectricTestRunner::class)
class MediaGeometryLedgerTest {
    private val url = "https://images.example.org/content"
    private val probe = IntrinsicMediaMetadata(IntSize(800, 600), "image/gif")
    private val painter = IntrinsicMediaMetadata(IntSize(320, 240), null)

    @Test
    fun `invalid dimensions cannot fix geometry or enrich mime`() {
        val ledger = MediaAttemptLedger()
        listOf(IntSize(0, 20), IntSize(20, 0), IntSize(-1, -1)).forEach { size ->
            assertFalse(
                ledger.acceptGeometry(url, 0, IntrinsicMediaMetadata(size, "image/gif"), MediaAttemptKind.PROBE),
            )
        }
        assertNull(ledger.geometryOf(url))
        ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PAINTER)
        ledger.acceptGeometry(url, 0, IntrinsicMediaMetadata(IntSize.Zero, "image/gif"), MediaAttemptKind.PROBE)
        assertEquals(painter.size, ledger.geometryOf(url)?.size)
        assertNull(ledger.geometryOf(url)?.mimeType)
    }

    @Test
    fun `probe first keeps dimensions provenance and mime after painter`() {
        val ledger = MediaAttemptLedger()
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE)
        ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PAINTER)
        assertEquals(MediaGeometry(probe.size, MediaAttemptKind.PROBE, "image/gif"), ledger.geometryOf(url))
    }

    @Test
    fun `painter first keeps dimensions and provenance while reliable probe enriches mime`() {
        val ledger = MediaAttemptLedger()
        ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PAINTER)
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE)
        assertEquals(MediaGeometry(painter.size, MediaAttemptKind.PAINTER, "image/gif"), ledger.geometryOf(url))
    }

    @Test
    fun `painter cannot supply mime even when its caller supplies one`() {
        val ledger = MediaAttemptLedger()
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PAINTER)
        assertNull(ledger.geometryOf(url)?.mimeType)
    }

    @Test
    fun `known mime is never cleared or replaced and disagreements are logged`() {
        val ledger = MediaAttemptLedger()
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE)
        ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PROBE)
        ledger.acceptGeometry(url, 0, probe.copy(mimeType = "image/jpeg"), MediaAttemptKind.PROBE)
        assertEquals("image/gif", ledger.geometryOf(url)?.mimeType)
        val logs = ShadowLog.getLogsForTag(MEDIA_GEOMETRY_LOG_TAG)
        assertTrue(logs.any { it.msg.contains("geometry disagreement") })
        assertTrue(logs.any { it.msg.contains("mime disagreement") })
    }

    @Test
    fun `concurrent valid pairs produce one indivisible authority`() {
        val ledger = MediaAttemptLedger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val candidates = listOf(MediaAttemptKind.PROBE to probe, MediaAttemptKind.PAINTER to painter)
            val results = candidates.map { (kind, data) ->
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    ledger.acceptGeometry(url, 0, data, kind)
                }
            }
            start.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            val geometry = checkNotNull(ledger.geometryOf(url))
            val expected = if (geometry.provenance == MediaAttemptKind.PROBE) probe.size else painter.size
            assertEquals(expected, geometry.size)
            assertEquals("image/gif", geometry.mimeType)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `memo eviction and replacement never affect ledger authority`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 1)
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE, cache)
        cache.putSuccess("other", painter)
        assertNull(cache.get(url))
        cache.putSuccess(url, painter)
        assertEquals(probe.size, ledger.geometryOf(url)?.size)
        assertTrue(ledger.hasSucceeded(url, MediaAttemptKind.PROBE))
        assertFalse(ledger.tryReserve(url, 0, MediaAttemptKind.PROBE))
    }

    @Test
    fun `manual retry and negative ttl preserve geometry and allow current mime enrichment`() {
        val ledger = MediaAttemptLedger()
        ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PAINTER)
        ledger.settleFailure(url, 0, MediaAttemptKind.PAINTER, 0L)
        assertEquals(1, ledger.consultGeneration(url, 60_000L))
        ledger.retryUrl(url)
        ledger.acceptGeometry(url, 2, probe, MediaAttemptKind.PROBE)
        assertEquals(MediaGeometry(painter.size, MediaAttemptKind.PAINTER, "image/gif"), ledger.geometryOf(url))
    }

    @Test
    fun `stale results neither establish nor enrich authority or memo`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        ledger.retryUrl(url)
        assertFalse(ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE, cache))
        assertFalse(ledger.acceptGeometry(url, 0, painter, MediaAttemptKind.PAINTER, cache))
        assertNull(ledger.geometryOf(url))
        assertNull(cache.get(url))
        ledger.acceptGeometry(url, 1, painter, MediaAttemptKind.PAINTER, cache)
        ledger.acceptGeometry(url, 0, probe, MediaAttemptKind.PROBE, cache)
        assertEquals(MediaGeometry(painter.size, MediaAttemptKind.PAINTER, null), ledger.geometryOf(url))
        assertEquals(painter, cache.get(url))
    }
}
