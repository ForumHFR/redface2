package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InlineQuoteRequestTest {
    @Test
    fun `pending coordinates retain order truncation page and ref through saved state`() {
        val quotes = listOf(
            QuoteSelection(QuoteLocator(4, 202, null), "bob", "preview", true),
            QuoteSelection(QuoteLocator(2, 101, 3), "alice", "preview"),
        )
        val restored = quotes.encodeInlineQuoteRequest().decodeInlineQuoteRequest()
        assertEquals(quotes.map { it.locator }, restored.map { it.locator })
        assertEquals(quotes.map { it.truncate }, restored.map { it.truncate })
        assertEquals(quotes.inlineQuoteDeliveryKey(), restored.inlineQuoteDeliveryKey())
        assertNotEquals(quotes.inlineQuoteDeliveryKey(), quotes.reversed().inlineQuoteDeliveryKey())
        assertNotEquals(
            quotes.inlineQuoteDeliveryKey(), quotes.map { it.copy(truncate = false) }.inlineQuoteDeliveryKey(),
        )
    }
}
