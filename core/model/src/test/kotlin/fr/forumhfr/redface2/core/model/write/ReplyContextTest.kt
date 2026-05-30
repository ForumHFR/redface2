package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `ReplyContext.init` rules for Phase 2C (#145 / #146). Each `require`
 * is a runtime guard against a wire shape we never want to send to HFR ; the
 * tests below describe the exact contract so future Phase 2D (#147 edit) code
 * does not relax it by accident.
 */
class ReplyContextTest {

    @Test
    fun `simple reply has no quote params and is not a quote`() {
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 1)
        assertFalse("reply must not be flagged as quote", context.isQuote)
    }

    @Test
    fun `quote with both quotedNumreponse and quoteRef is accepted`() {
        val context = ReplyContext(
            cat = 23,
            subcat = 550,
            topicId = 35395,
            page = 1,
            quotedNumreponse = 2_784_595,
            quoteRef = 0,
        )
        assertTrue(context.isQuote)
        assertEquals(2_784_595, context.quotedNumreponse)
        assertEquals(0, context.quoteRef)
    }

    @Test
    fun `quote without quoteRef stays accepted for forward compat`() {
        // HFR is documented as eventually able to drop `ref` from the quote
        // contract — `HfrClient.getReplyForm` keeps that shape tolerant. The
        // model must therefore not assert `quoteRef != null` when quoting.
        val context = ReplyContext(
            cat = 23,
            subcat = 550,
            topicId = 35395,
            page = 1,
            quotedNumreponse = 2_784_595,
            quoteRef = null,
        )
        assertTrue(context.isQuote)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `quoteRef without quotedNumreponse is rejected`() {
        // `(null, set)` is the only truly nonsense shape — `ref` only makes
        // sense when paired with a cited post. We refuse it loudly so a
        // future bug at the navigation or VM layer surfaces immediately.
        ReplyContext(
            cat = 23,
            subcat = 550,
            topicId = 35395,
            page = 1,
            quotedNumreponse = null,
            quoteRef = 0,
        )
    }

    @Test
    fun `subcat zero is accepted as a category without sub-category (cat IA)`() {
        // #213 — `subcat = 0` is HFR's wire shape for a category WITHOUT a
        // sub-category (e.g. cat=32 « Intelligence artificielle »). A live capture
        // of the IA reply form proved HFR posts with `subcat=0` (see
        // protocol-hfr.md § POST bddpost.php), so it must NOT throw : the reply
        // form was present, the topic is postable.
        val context = ReplyContext(cat = 32, subcat = 0, topicId = 1, page = 1)
        assertEquals(0, context.subcat)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subcat sentinel -1 is rejected`() {
        ReplyContext(cat = 23, subcat = -1, topicId = 1, page = 1)
    }
}
