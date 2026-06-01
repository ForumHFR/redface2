package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the quote-recursion-depth contract spelled out in issue #3
 * (https://github.com/ForumHFR/redface2/issues/3) — "Max N=3 niveaux visibles, reste collapsible
 * ('Afficher les citations imbriquées'). Au-delà, HFR est illisible de toute façon. Évite
 * l'explosion de stack et limite la récursion d'UI."
 *
 * The actual rendering branch lives in the `@Composable` `QuoteBlock`, which needs Robolectric
 * to drive — that's tracked separately under issue #130 alongside the `fillMaxSize()` /
 * `fontScale` invariants. The decision predicate `isCollapsedQuoteDepth` was extracted out of
 * `QuoteBlock` so the contract can be locked in a pure JVM test today and any later change
 * (e.g. lowering N to 2 or raising it to 4) becomes a deliberate review step rather than a
 * silent drift.
 */
class PostRendererQuoteDepthTest {

    @Test
    fun `MAX_VISIBLE_QUOTE_DEPTH stays at the issue 3 contract value`() {
        assertEquals(
            "Issue #3 mandates N=3 visible quote levels — bumping this constant must be a " +
                "deliberate review step, not a silent change.",
            3,
            MAX_VISIBLE_QUOTE_DEPTH,
        )
    }

    @Test
    fun `quote depths below the limit render expanded`() {
        // The first three nesting levels (0, 1, 2) must render as a regular `Card` quote so the
        // typical 1- and 2-level quote chains found in HFR threads stay readable.
        assertFalse("depth 0 (top-level quote) must render expanded", isCollapsedQuoteDepth(0))
        assertFalse("depth 1 (quote-in-quote) must render expanded", isCollapsedQuoteDepth(1))
        assertFalse(
            "depth 2 (quote-in-quote-in-quote) must render expanded",
            isCollapsedQuoteDepth(2),
        )
    }

    @Test
    fun `quote depth at and beyond the limit must collapse`() {
        // Depth 3 is the first level that crosses the issue #3 threshold, so the predicate
        // returns true and `QuoteBlock` will branch to `CollapsedQuoteBlock` at the call site.
        // The reveal/reset behaviour of `CollapsedQuoteBlock` itself (clicking re-expands with
        // depth reset to 0) lives inside the `@Composable` and is tracked under issue #130.
        assertTrue(
            "depth 3 (4th nested quote) must collapse to the 'Afficher' card per issue #3",
            isCollapsedQuoteDepth(3),
        )
        assertTrue("depth 4 must collapse", isCollapsedQuoteDepth(4))
        assertTrue("very deep quotes must keep collapsing", isCollapsedQuoteDepth(99))
    }

    @Test
    fun `a bare quote always uses the neutral accent regardless of depth`() {
        // Issue #252 — a hand-typed `[quote]` (author == null) is the user formatting their own
        // text, never a sourced citation, so it must read with the neutral `outline` accent at
        // every nesting level (it never alternates into the primary/tertiary citation palette).
        assertEquals(QuoteAccentRole.BARE, quoteAccentRole(quoteDepth = 0, isBareQuote = true))
        assertEquals(QuoteAccentRole.BARE, quoteAccentRole(quoteDepth = 1, isBareQuote = true))
        assertEquals(QuoteAccentRole.BARE, quoteAccentRole(quoteDepth = 2, isBareQuote = true))
    }

    @Test
    fun `isBareQuote is true only when a quote carries no source metadata`() {
        fun quote(author: String?, numreponse: Int?, page: Int?) = PostBlock.Quote(
            author = author,
            numreponse = numreponse,
            page = page,
            content = PostContent(blocks = emptyList()),
        )
        // Hand-typed [quote]: no author, no numreponse, no page → bare.
        assertTrue(isBareQuote(quote(author = null, numreponse = null, page = null)))
        // #254 regression guard: a sourced [quotemsg=id,page,user] parsed in the editor preview has
        // author == null but a non-null numreponse — it must NOT be treated as bare.
        assertFalse(isBareQuote(quote(author = null, numreponse = 2523833, page = null)))
        assertFalse(isBareQuote(quote(author = null, numreponse = 2523833, page = 1)))
        // Reading-path sourced citation: author present → not bare.
        assertFalse(isBareQuote(quote(author = "Lt Ripley", numreponse = 74749781, page = 8270)))
    }

    @Test
    fun `a sourced citation keeps the primary-tertiary alternation by depth`() {
        // A real HFR citation (`[quotemsg=]`, author set) keeps the issue #202 hierarchy: even
        // depths get `primary`, odd depths `tertiary`, so nested citations stay visually layered
        // and remain clearly distinct from the bare-quote neutral accent above.
        assertEquals(QuoteAccentRole.SOURCED_EVEN, quoteAccentRole(quoteDepth = 0, isBareQuote = false))
        assertEquals(QuoteAccentRole.SOURCED_ODD, quoteAccentRole(quoteDepth = 1, isBareQuote = false))
        assertEquals(QuoteAccentRole.SOURCED_EVEN, quoteAccentRole(quoteDepth = 2, isBareQuote = false))
        assertEquals(QuoteAccentRole.SOURCED_ODD, quoteAccentRole(quoteDepth = 3, isBareQuote = false))
    }
}
