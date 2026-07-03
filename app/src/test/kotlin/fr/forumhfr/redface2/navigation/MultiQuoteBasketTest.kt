package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #291/#604 lot 2 — the hoisted multi-quote basket logic, enriched from bare numreponses to
 * [QuotedPostPreview] entries. Pinned contracts: selection ORDER is the tap order, uniqueness and
 * removal are keyed on the numreponse alone (whatever snapshot the caller rebuilt), selecting in
 * another topic replaces the basket, and removing the last entry clears it to null.
 */
class MultiQuoteBasketTest {

    @Test
    fun `selections accumulate in tap order`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(CAT, POST, preview(101, "alice"))
            .toggled(CAT, POST, preview(303, "carol"))
            .toggled(CAT, POST, preview(202, "bob"))

        assertEquals(listOf(101, 303, 202), basket?.numreponses)
        assertEquals(listOf("alice", "carol", "bob"), basket?.selections?.map { it.author })
    }

    @Test
    fun `re-toggling removes by numreponse whatever the snapshot carries`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(CAT, POST, preview(101, "alice"))
            .toggled(CAT, POST, preview(202, "bob"))
            // The caller rebuilt a DIFFERENT snapshot of the same post (author edited, say).
            .toggled(CAT, POST, preview(101, "alice-edited"))

        assertEquals(listOf(202), basket?.numreponses)
    }

    @Test
    fun `removing the last entry clears the basket to null`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(CAT, POST, preview(101, "alice"))
            .toggled(CAT, POST, preview(101, "alice"))

        assertNull(basket)
    }

    @Test
    fun `selecting in another topic replaces the basket`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(CAT, POST, preview(101, "alice"))
            .toggled(CAT, POST + 1, preview(555, "dave"))

        assertEquals(POST + 1, basket?.post)
        assertEquals(listOf(555), basket?.numreponses)
    }

    private fun preview(numreponse: Int, author: String): QuotedPostPreview =
        QuotedPostPreview(numreponse = numreponse, author = author, excerpt = "extrait de $author")

    private companion object {
        const val CAT = 23
        const val POST = 35421
    }
}
