package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.EditorChromeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `ED140 portrait uses R1a1 chrome and caps cards while retaining the field floor`() {
        val chrome = EditorChromeMetrics(false)
        val fieldArea = chrome.draftHeight(500.dp)
        val allocation = allocatePostEditorQuotes(fieldArea, chrome,
            PostEditorFieldMetrics(24.dp, 32.dp, 3), 56.dp, true)
        assertEquals(112.dp, allocation.zoneHeight)
        assertEquals(272.dp, allocation.fieldHeight)
        assertFalse(allocation.panelOnly)
    }

    @Test fun `P22 uses the measured whole card boundary instead of a 48 dp constant`() {
        val field = PostEditorFieldMetrics(24.dp, 16.dp, 3)
        val chrome = EditorChromeMetrics(true)
        val below = allocatePostEditorQuotes(161.dp, chrome, field, 74.dp, true)
        val at = allocatePostEditorQuotes(162.dp, chrome, field, 74.dp, true)
        assertEquals(0.dp, below.zoneHeight)
        assertTrue(below.panelOnly)
        assertEquals(161.dp, below.fieldHeight)
        assertEquals(74.dp, at.zoneHeight)
        assertEquals(88.dp, at.fieldHeight)
    }

    @Test fun `J preserves three or two real lines without claiming to solve insufficient windows`() {
        listOf(PostEditorFieldMetrics(24.dp, 16.dp, 3), PostEditorFieldMetrics(31.2.dp, 20.8.dp, 2)).forEach {
            val result = allocatePostEditorQuotes(90.dp, EditorChromeMetrics(true), it, 56.dp, true)
            assertEquals(0.dp, result.zoneHeight)
            assertEquals(90.dp, result.fieldHeight)
            assertTrue(result.panelOnly)
        }
        val insufficient = allocatePostEditorQuotes(41.dp, EditorChromeMetrics(true),
            PostEditorFieldMetrics(31.2.dp, 20.8.dp, 2), 56.dp, true)
        assertEquals(41.dp, insufficient.fieldHeight)
        assertTrue(insufficient.panelOnly)
    }

    @Test fun `ED03 no card means no exception zone or quote status`() {
        val result = allocatePostEditorQuotes(300.dp, EditorChromeMetrics(false),
            PostEditorFieldMetrics(24.dp, 32.dp, 3), 56.dp, false)
        assertEquals(PostEditorQuoteAllocation(0.dp, 300.dp, false), result)
    }
}
