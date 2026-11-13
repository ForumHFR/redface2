package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteScope
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertEquals(listOf(selection(101, "alice"), selection(303, "carol"), selection(202, "bob")),
            basket?.selections)
        assertEquals(listOf("alice", "carol", "bob"), basket?.selections?.map { it.author })
    }

    @Test
    fun `re-toggling removes by numreponse whatever the snapshot carries`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(topicScope, selection(202, "bob"))
            // The caller rebuilt a DIFFERENT snapshot and locator for the same post.
            .toggled(topicScope, selection(101, "alice-edited", page = 4, ref = null))

        assertEquals(listOf(selection(202, "bob")), basket?.selections)
    }

    @Test
    fun `removing the last entry clears the basket to null`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(topicScope, selection(101, "alice"))

        assertNull(basket)
    }

    @Test
    fun `ED88 a basket-consuming session of the same target consumes the basket`() {
        val basket = (null as MultiQuoteBasket?).toggled(topicScope, selection(101, "alice"))

        assertTrue(basket.isConsumedBy(topicScope, consumesBasket = true))
    }

    @Test
    fun `ED88 a session that never shipped the basket leaves it armed`() {
        val basket = (null as MultiQuoteBasket?).toggled(topicScope, selection(101, "alice"))

        // « Citer » simple, plain reply, #823 long-press: decided by the open path, not the quotes.
        assertFalse(basket.isConsumedBy(topicScope, consumesBasket = false))
    }

    @Test
    fun `ED88 a basket re-armed on another target is never consumed`() {
        val elsewhere = (null as MultiQuoteBasket?)
            .toggled(QuoteScope.Topic(CAT, POST + 1), selection(555, "dave"))
        val privateBasket = (null as MultiQuoteBasket?)
            .toggled(QuoteScope.PrivateMessage(POST), selection(101, "alice"))

        assertFalse(elsewhere.isConsumedBy(topicScope, consumesBasket = true))
        assertFalse(privateBasket.isConsumedBy(topicScope, consumesBasket = true))
        assertTrue(privateBasket.isConsumedBy(QuoteScope.PrivateMessage(POST), consumesBasket = true))
    }

    @Test
    fun `ED88 no basket and no writing target consume nothing`() {
        val basket = (null as MultiQuoteBasket?).toggled(topicScope, selection(101, "alice"))

        assertFalse((null as MultiQuoteBasket?).isConsumedBy(topicScope, consumesBasket = true))
        // A new-topic editor has no topic id, hence no scope.
        assertFalse(basket.isConsumedBy(null, consumesBasket = true))
    }

    @Test
    fun `ED88 buffered quick reply delivery applies to the current basket and its exact target`() {
        var basket = (null as MultiQuoteBasket?).toggled(topicScope, selection(101, "alice"))
        // Capture the sender, as the sheet does; the receiver reads the current hoisted basket.
        val bufferedDelivery = { basket = basket.consumedBy(topicScope) }
        val otherTargets = listOf(
            QuoteScope.Topic(CAT, POST + 1),
            QuoteScope.Topic(CAT + 1, POST),
            QuoteScope.PrivateMessage(POST),
        )
        for (target in otherTargets) {
            basket = basket.toggled(target, selection(555, "dave"))
            val rearmed = basket
            bufferedDelivery()
            assertEquals(rearmed, basket)
        }
        basket = basket.toggled(topicScope, selection(101, "alice"))
        bufferedDelivery()
        assertNull(basket)
        bufferedDelivery() // A repeated or late delivery with no basket stays harmless.
        assertNull(basket)
    }

    @Test
    fun `selecting in another topic replaces the basket`() {
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(QuoteScope.Topic(CAT, POST + 1), selection(555, "dave"))

        assertEquals(QuoteScope.Topic(CAT, POST + 1), basket?.scope)
        assertEquals(listOf(selection(555, "dave")), basket?.selections)
    }

    @Test
    fun `hidden selections are removed only from the matching scope`() {
        val privateScope = QuoteScope.PrivateMessage(threadId = 4_242_424)
        val basket = MultiQuoteBasket(
            scope = privateScope,
            selections = listOf(
                selection(101, "alice", page = 2, ref = 7),
                selection(202, "bob", page = 4, ref = 3),
                selection(303, "alice", page = 8, ref = 1),
            ),
        )

        val pruned = basket.withoutSelections(privateScope, setOf(101, 303))

        assertEquals(listOf(selection(202, "bob", page = 4, ref = 3)), pruned?.selections)
        assertEquals(basket, basket.withoutSelections(topicScope, setOf(101)))
    }

    @Test
    fun `removing every hidden selection clears the basket`() {
        val basket = MultiQuoteBasket(topicScope, listOf(selection(101, "alice")))

        assertNull(basket.withoutSelections(topicScope, setOf(101)))
    }

    @Test
    fun `1105 basket never exposes ids stripped of their scope and snapshots`() {
        assertFalse(MultiQuoteBasket::class.java.methods.any { it.name == "getNumreponses" })
    }

    @Test
    fun `same numeric id in another category replaces the scoped snapshot`() {
        val otherScope = QuoteScope.Topic(CAT + 1, POST)
        val quote = selection(101, "other author", page = 7)
        val basket = (null as MultiQuoteBasket?)
            .toggled(topicScope, selection(101, "alice"))
            .toggled(otherScope, quote)

        assertEquals(otherScope, basket?.scope)
        assertEquals(listOf(quote), basket?.selections)
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

    @Test
    fun `private-message editor handoff keeps complete locators only for its conversation`() {
        val privateScope = QuoteScope.PrivateMessage(threadId = 4_242_424)
        val quote = selection(101, "alice", page = 7, ref = 3)
        val handoff = EditorQuotesHandoff(
            scope = privateScope,
            quotes = listOf(quote),
            consumesBasket = true,
        )

        assertEquals(listOf(quote), handoff.forScope(privateScope)?.quotes)
        assertNull(handoff.forScope(QuoteScope.PrivateMessage(threadId = 4_242_425)))
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
