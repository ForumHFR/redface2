package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateMessageQuoteTest {

    @Test
    fun `positive message id and 1-based ref are accepted`() {
        val quote = PrivateMessageQuote(numreponse = 1_980_000_004, ref = 4)

        assertEquals(1_980_000_004, quote.numreponse)
        assertEquals(4, quote.ref)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing-rank sentinel zero is rejected fail closed`() {
        PrivateMessageQuote(numreponse = 1_980_000_004, ref = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive message id is rejected`() {
        PrivateMessageQuote(numreponse = 0, ref = 1)
    }
}
