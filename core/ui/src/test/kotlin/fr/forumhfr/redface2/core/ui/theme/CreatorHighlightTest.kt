package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #221 — pure-JVM guard on [creatorSheenStops]: the gold sheen gradient must always produce strictly
 * increasing colour stops, including at the band's extremities where a naive build duplicates an
 * endpoint (`travel = 0` puts the band's right edge exactly on `0f`; `travel = 1` puts its left edge
 * exactly on `1f`). `Brush.linearGradient` rejects out-of-order or duplicate stops, so this invariant is
 * what keeps the animation from crashing mid-sweep.
 */
class CreatorHighlightTest {

    private val base = Color(0xFF7A5C00)
    private val highlight = Color(0xFF946E00)

    @Test
    fun `stops are strictly increasing across the whole sweep`() {
        // 101 evenly-spaced samples so the loop lands EXACTLY on both extremities (travel=0f at i=0,
        // travel=1f at i=100) — those are the degenerate cases (band edge on 0f / 1f) the strict
        // (0, 1) guards must keep from duplicating an endpoint.
        (0..SAMPLES).forEach { i ->
            val travel = i.toFloat() / SAMPLES
            val stops = creatorSheenStops(travel, base, highlight)
            for (j in 1 until stops.size) {
                assertTrue(
                    "stops not strictly increasing at travel=$travel: ${stops.map { it.first }}",
                    stops[j].first > stops[j - 1].first,
                )
            }
        }
    }

    @Test
    fun `the gradient always spans the full 0f to 1f range`() {
        listOf(0f, 0.1f, 0.5f, 0.9f, 1f).forEach { travel ->
            val stops = creatorSheenStops(travel, base, highlight)
            assertEquals("first stop at travel=$travel", 0f, stops.first().first)
            assertEquals("last stop at travel=$travel", 1f, stops.last().first)
        }
    }

    @Test
    fun `mid-sweep carries a highlight band, extremities rest on base gold`() {
        // At travel=0.5 the band centre sits in the middle of the text, so the highlight colour is present.
        assertTrue(creatorSheenStops(0.5f, base, highlight).any { it.second == highlight })
        // At the extremities the band has slid off the text: only base gold remains.
        assertTrue(creatorSheenStops(0f, base, highlight).all { it.second == base })
        assertTrue(creatorSheenStops(1f, base, highlight).all { it.second == base })
    }

    private companion object {
        const val SAMPLES = 100
    }
}
