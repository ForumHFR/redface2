package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagRowInteractionsTest {

    @Test
    fun `orphan Super fallback keeps long-press actions but cannot open the topic`() {
        val row = flag(cat = 0).toFlagRowUiModel(MarkerStyle.PASTILLE)

        assertFalse(flagRowClickEnabled(row))
        assertTrue(flagRowActionsEnabled(row))
    }

    @Test
    fun `regular flag rows keep tap and long-press actions`() {
        val row = flag(cat = 23).toFlagRowUiModel(MarkerStyle.PASTILLE)

        assertTrue(flagRowClickEnabled(row))
        assertTrue(flagRowActionsEnabled(row))
    }

    private fun flag(cat: Int): Flag = Flag(
        cat = cat,
        subcat = null,
        topicId = 35395,
        title = "Topic Redface 2",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.FAVORITE,
        isFavorite = true,
        hasUnread = false,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "XaTriX",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )
}
