package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 100% coverage of the pure presentation helpers introduced for the #603 Drapeaux refonte
 * (ADR-017): [pagesToRead], [effectiveFlagColor] and [toFlagRowUiModel]. No Android dependency —
 * raw [Flag] in, plain values out, so the list/marker rendering (PR3) never recomputes them in
 * composition.
 */
class FlagPresentationTest {

    // --- pagesToRead -------------------------------------------------------------------------

    @Test
    fun `pagesToRead is the number of pages after the last read one`() {
        assertEquals(3, baseFlag.copy(totalPages = 10, lastReadPage = 7).pagesToRead())
    }

    @Test
    fun `pagesToRead is zero when the topic is fully read`() {
        assertEquals(0, baseFlag.copy(totalPages = 10, lastReadPage = 10).pagesToRead())
    }

    @Test
    fun `pagesToRead clamps to zero when lastReadPage exceeds totalPages (stale data)`() {
        // Defensive: a stale cache could carry a lastReadPage past a shrunk totalPages.
        assertEquals(0, baseFlag.copy(totalPages = 5, lastReadPage = 8).pagesToRead())
    }

    @Test
    fun `pagesToRead on a barely-read long topic`() {
        assertEquals(40, baseFlag.copy(totalPages = 41, lastReadPage = 1).pagesToRead())
    }

    @Test
    fun `pagesToRead with an unset last-read page (0) returns the full page count`() {
        // Defensive boundary: REST defaults lastReadPage to 1, but a missing marker (0) must not
        // over-count — totalPages - 0 = totalPages, never more.
        assertEquals(7, baseFlag.copy(totalPages = 7, lastReadPage = 0).pagesToRead())
    }

    // --- effectiveFlagColor ------------------------------------------------------------------

    @Test
    fun `effective color is the bucket type when the topic is not a favorite`() {
        assertEquals(FlagType.CYAN, baseFlag.copy(type = FlagType.CYAN, isFavorite = false).effectiveFlagColor())
        assertEquals(FlagType.RED, baseFlag.copy(type = FlagType.RED, isFavorite = false).effectiveFlagColor())
    }

    @Test
    fun `effective color is FAVORITE when the topic is favorited regardless of bucket`() {
        // #384/dev v118 parity: a favorited topic listed under « Mes sujets » keeps its yellow marker.
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.CYAN, isFavorite = true).effectiveFlagColor())
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.RED, isFavorite = true).effectiveFlagColor())
    }

    @Test
    fun `effective color of a FAVORITE bucket flag is FAVORITE`() {
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.FAVORITE).effectiveFlagColor())
    }

    // --- toFlagRowUiModel --------------------------------------------------------------------

    @Test
    fun `row ui model bundles the derived presentation values`() {
        val f = baseFlag.copy(
            type = FlagType.CYAN,
            isFavorite = false,
            hasUnread = true,
            totalPages = 12,
            lastReadPage = 9,
        )

        val ui = f.toFlagRowUiModel(MarkerStyle.STRIPE)

        assertEquals(f, ui.flag)
        assertEquals(3, ui.pagesToRead)
        assertEquals(FlagType.CYAN, ui.effectiveColor)
        assertEquals(MarkerStyle.STRIPE, ui.markerStyle)
        assertFalse("an unread flag is not dimmed", ui.dimmed)
    }

    @Test
    fun `row ui model is dimmed for a read flag`() {
        val ui = baseFlag.copy(hasUnread = false).toFlagRowUiModel(MarkerStyle.DOT)

        assertTrue("a fully-read flag is dimmed", ui.dimmed)
    }

    @Test
    fun `row ui model carries the requested marker style and favorite color`() {
        val ui = baseFlag.copy(type = FlagType.CYAN, isFavorite = true).toFlagRowUiModel(MarkerStyle.PASTILLE)

        assertEquals(MarkerStyle.PASTILLE, ui.markerStyle)
        assertEquals(FlagType.FAVORITE, ui.effectiveColor)
    }

    private val baseFlag = Flag(
        cat = 1,
        subcat = null,
        topicId = 1,
        title = "Topic",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        isFavorite = false,
        hasUnread = true,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "op",
        lastReplyAuthor = "last",
        lastReplyAt = "2026-06-24 12:00",
    )
}
