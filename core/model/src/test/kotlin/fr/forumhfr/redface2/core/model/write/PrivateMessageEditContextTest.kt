package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageEditContextTest {
    @Test
    fun `toString never exposes private coordinates`() {
        val context = PrivateMessageEditContext(3000003, 17, 1980000302)

        val rendered = context.toString()

        listOf(context.threadId, context.page, context.numreponse).forEach { coordinate ->
            assertFalse("toString must not expose private coordinates", rendered.contains(coordinate.toString()))
        }
        assertEquals("PrivateMessageEditContext([redacted])", rendered)
    }

    @Test
    fun `private edit retains the real page without a numeric category`() {
        assertEquals(4, PrivateMessageEditContext(3000003, 4, 1980000302).page)
    }

    @Test
    fun `invalid private coordinates are rejected`() {
        listOf(
            { PrivateMessageEditContext(0, 1, 1) },
            { PrivateMessageEditContext(1, 0, 1) },
            { PrivateMessageEditContext(1, 1, 0) },
            { PrivateMessageEditContext(-1, 1, 1) },
            { PrivateMessageEditContext(1, -1, 1) },
            { PrivateMessageEditContext(1, 1, -1) },
        ).forEach { create ->
            assertTrue(runCatching { create() }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
