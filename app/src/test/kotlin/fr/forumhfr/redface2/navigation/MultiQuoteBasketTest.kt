package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteScope
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #291/#604/#1074 — the hoisted multi-quote basket logic. Pinned contracts: selection ORDER is the
 * tap order, uniqueness and removal are keyed on `(scope, numreponse)` whatever snapshot/locator
 * the caller rebuilt, selecting in another scope replaces the basket, and removing the last entry
 * clears it to null.
 */
class MultiQuoteBasketTest {

    @Test
    fun `selections accumulate in tap order`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(topicScope, selection(303, "carol"))
            .toggled(topicScope, selection(202, "bob"))

        assertEquals(listOf(101, 303, 202), basket?.numreponses)
        assertEquals(listOf("alice", "carol", "bob"), basket?.selections?.map { it.author })
    }

    @Test
    fun `re-toggling removes by numreponse whatever the snapshot carries`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(topicScope, selection(202, "bob"))
            // The caller rebuilt a DIFFERENT snapshot and locator for the same post.
            .toggled(topicScope, selection(101, "alice-edited", page = 4, ref = null))

        assertEquals(listOf(202), basket?.numreponses)
    }

    @Test
    fun `removing the last entry clears the basket to null`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(topicScope, selection(101, "alice"))

        assertNull(basket)
    }

    @Test
    fun `selecting in another topic replaces the basket`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(QuoteScope.Topic(CAT, POST + 1), selection(555, "dave"))

        assertEquals(QuoteScope.Topic(CAT, POST + 1), basket?.scope)
        assertEquals(listOf(555), basket?.numreponses)
    }

    @Test
    fun `private-message scope is representable without a numeric category`() {
        val privateScope = QuoteScope.PrivateMessage(threadId = 4_242_424)

        val basket = (null as MultiQuoteBasket?)
            .toggled(privateScope, selection(101, "alice", page = 2, ref = 7))

        assertEquals(privateScope, basket?.scope)
        assertEquals(
            QuoteLocator(page = 2, numreponse = 101, ref = 7),
            basket?.selections?.single()?.locator,
        )
    }

    @Test
    fun `editor handoff is visible only to its exact scope`() {
        val handoff = EditorQuotesHandoff(
            scope = topicScope,
            quotes = listOf(selection(101, "alice")),
            consumesBasket = true,
        )

        assertEquals(handoff, handoff.forScope(topicScope))
        assertNull(handoff.forScope(QuoteScope.Topic(CAT, POST + 1)))
        assertNull(handoff.forScope(QuoteScope.PrivateMessage(threadId = POST)))
        assertNull(handoff.forScope(null))
    }

    private fun selection(
        numreponse: Int,
        author: String,
        page: Int = 3,
        ref: Int? = 1,
    ): QuoteSelection = QuoteSelection(
        locator = QuoteLocator(page = page, numreponse = numreponse, ref = ref),
        author = author,
        excerpt = "extrait de $author",
    )

    private val topicScope = QuoteScope.Topic(CAT, POST)

    private companion object {
        const val CAT = 23
        const val POST = 35421
    }
}
