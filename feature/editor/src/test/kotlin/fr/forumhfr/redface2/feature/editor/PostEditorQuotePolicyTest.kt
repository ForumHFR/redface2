package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class PostEditorQuotePolicyTest {
    private val quote = "[quotemsg=101,2,7]Même texte[/quotemsg]"

    @Test fun `ED52 same content is suppressed but another portion and another post survive`() {
        val otherPortion = "[quotemsg=101,2,7]Autre portion[/quotemsg]"
        val otherPost = "[quotemsg=202,2,7]Même texte[/quotemsg]"
        val result = filterInlineQuoteDelivery(23, 23, "$quote\n\n$otherPortion\n\n$otherPost", quote)
        assertEquals(1, result.duplicates)
        assertEquals("$otherPortion\n\n$otherPost", result.bbcode)
    }

    @Test fun `ED52 numreponse never crosses the known category scope`() {
        assertEquals(PostEditorQuoteDelivery(quote, 0), filterInlineQuoteDelivery(22, 23, quote, quote))
    }

    @Test fun `ED52 nested blocks compare intact and incomplete blocks are retained`() {
        val nested = "[quotemsg=202]$quote\nRéponse[/quotemsg]"
        assertEquals(PostEditorQuoteDelivery("", 1), filterInlineQuoteDelivery(23, 23, nested, nested))
        assertEquals(PostEditorQuoteDelivery("", 1), filterInlineQuoteDelivery(23, 23, quote, nested))
        assertEquals(PostEditorQuoteDelivery(quote, 0), filterInlineQuoteDelivery(23, 23, quote, quote.dropLast(1)))
        val changedMetadata = "[QUOTEMSG=101,99,7]Même texte[/QUOTEMSG]"
        assertEquals(1, filterInlineQuoteDelivery(23, 23, changedMetadata, quote).duplicates)
    }

    @Test fun `ED52 duplicate deliveries in one batch retain the first and preserve order`() {
        assertEquals(PostEditorQuoteDelivery(quote, 1), filterInlineQuoteDelivery(23, 23, "$quote\n\n$quote", ""))
        val first = QuoteSelection(QuoteLocator(1, 101, null), "Alice", "Début")
        val portion = first.copy(excerpt = "Suite")
        val second = first.copy(locator = first.locator.copy(numreponse = 202))
        assertEquals(listOf(first, portion, second), distinctQuoteCards(23,
            listOf(first, first.copy(locator = first.locator.copy(page = 2)), portion, second)))
    }
}
