package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #895 étape 4 (PR 2, gate r1) — the list-alignment marker that keeps a position persist from
 * recording page N's coordinates under page N+1 while a switch's landing is still pending.
 * The two scenarios below are the regression cases required by the gate.
 */
class TopicListAlignmentTest {

    @Test
    fun `a late fling settle after an LRU switch is ignored until the landing applies`() {
        val alignment = TopicListAlignment()
        // Entry landing applied on page 2 : settles persist for page 2.
        alignment.onLandingApplied(page = 2)
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))

        // The user flings, then switches to page 3 whose LRU snapshot activates instantly
        // (canonical = 3, mode = Loaded) — the fling settle fires BEFORE page 3's landing:
        // the list still sits at page 2's offset, so the persist must be skipped.
        assertFalse(
            "a settle between content swap and landing must not persist under the new page",
            alignment.shouldPersist(canonicalPage = 3, isLoaded = true),
        )

        // Page 3's landing applies : from here the position genuinely describes page 3.
        alignment.onLandingApplied(page = 3)
        assertTrue(alignment.shouldPersist(canonicalPage = 3, isLoaded = true))
        assertEquals(3, alignment.alignedPage)
    }

    @Test
    fun `a disposal racing a fresh switch is ignored before the landing applies`() {
        val alignment = TopicListAlignment()
        alignment.onLandingApplied(page = 5)

        // Switch to page 6 (snapshot → immediately Loaded), then the screen is disposed
        // (back / editor push) BEFORE page 6's landing effect ran : saving now would record
        // page 5's visual position under page 6.
        assertFalse(
            "a disposal before the switched page's landing must not save under it",
            alignment.shouldPersist(canonicalPage = 6, isLoaded = true),
        )

        // And a page that never reached Loaded never persists, aligned or not.
        assertFalse(alignment.shouldPersist(canonicalPage = 5, isLoaded = false))
    }

    @Test
    fun `nothing persists before the first landing`() {
        val alignment = TopicListAlignment()
        assertEquals(null, alignment.alignedPage)
        assertFalse(
            "the entry page must not persist before its entry landing ran",
            alignment.shouldPersist(canonicalPage = 1, isLoaded = true),
        )
    }
}
