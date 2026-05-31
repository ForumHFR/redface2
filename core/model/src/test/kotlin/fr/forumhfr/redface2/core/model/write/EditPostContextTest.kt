package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `EditPostContext.init` subcat contract (#213). Mirrors `ReplyContext` :
 * `subcat = 0` (a category without sub-category, e.g. cat IA) is postable, only the
 * SUBCAT_UNKNOWN sentinel (-1) is rejected.
 */
class EditPostContextTest {

    @Test
    fun `subcat zero is accepted as a category without sub-category`() {
        val context = EditPostContext(cat = 32, subcat = 0, topicId = 1, page = 1, numreponse = 5)
        assertEquals(0, context.subcat)
    }

    @Test
    fun `positive subcat is accepted`() {
        val context = EditPostContext(cat = 23, subcat = 550, topicId = 35395, page = 1, numreponse = 5)
        assertEquals(550, context.subcat)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subcat sentinel -1 is rejected`() {
        EditPostContext(cat = 23, subcat = -1, topicId = 1, page = 1, numreponse = 5)
    }
}
