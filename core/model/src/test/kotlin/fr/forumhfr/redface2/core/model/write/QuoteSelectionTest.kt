package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteSelectionTest {

    @Test
    fun `topic and private-message scopes cannot collide`() {
        val topic = QuoteScope.Topic(cat = 23, topicId = 4_242_424)
        val privateMessage = QuoteScope.PrivateMessage(threadId = 4_242_424)

        assertNotEquals(topic, privateMessage)
    }

    @Test
    fun `selection retains the complete locator`() {
        val selection = QuoteSelection(
            locator = QuoteLocator(page = 7, numreponse = 123_456, ref = 4),
            author = "alice",
            excerpt = "extrait",
        )

        assertEquals(7, selection.locator.page)
        assertEquals(123_456, selection.numreponse)
        assertEquals(4, selection.locator.ref)
    }

    @Test
    fun `missing ref stays explicit for topic fallback`() {
        val locator = QuoteLocator(page = 2, numreponse = 123_456, ref = null)

        assertNull(locator.ref)
    }
}
