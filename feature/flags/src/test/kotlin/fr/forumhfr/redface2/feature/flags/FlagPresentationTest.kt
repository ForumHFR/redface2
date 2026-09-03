package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the VM-side bundle [toFlagRowUiModel] (#603, ADR-017). The pure per-flag derivations it
 * relies on ([Flag.pagesToRead], [Flag.effectiveFlagColor]) are tested in `:core:model`
 * (`FlagDerivationsTest`) where they now live; here we only assert the bundle wiring.
 */
class FlagPresentationTest {

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

    @Test
    fun `row ui model carries the resolved subcategory name`() {
        val ui = baseFlag.toFlagRowUiModel(MarkerStyle.STRIPE, subcatName = "Android")

        assertEquals("Android", ui.subcatName)
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
