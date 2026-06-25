package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the pure [flagTopicUrl] builder (#603 PR5). */
class FlagTopicUrlTest {

    @Test
    fun `builds the forum2 permalink resuming at the last-read page`() {
        assertEquals(
            "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=13&post=35395&page=412",
            flagTopicUrl(flag(cat = 13, topicId = 35395, lastReadPage = 412)),
        )
    }

    @Test
    fun `coerces a missing last-read page (0) to page 1`() {
        assertEquals(
            "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=1&post=2&page=1",
            flagTopicUrl(flag(cat = 1, topicId = 2, lastReadPage = 0)),
        )
    }

    private fun flag(cat: Int, topicId: Int, lastReadPage: Int): Flag = Flag(
        cat = cat,
        subcat = null,
        topicId = topicId,
        title = "T",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        isFavorite = false,
        hasUnread = true,
        lastReadPage = lastReadPage,
        lastPostReadId = null,
        firstPostAuthor = "op",
        lastReplyAuthor = "last",
        lastReplyAt = "2026-06-24 12:00",
    )
}
