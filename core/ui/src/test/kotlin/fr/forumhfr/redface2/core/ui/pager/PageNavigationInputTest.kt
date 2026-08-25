package fr.forumhfr.redface2.core.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Test

class PageNavigationInputTest {

    @Test
    fun `strips non-digit characters from the raw input`() {
        assertEquals("123", coercePageJumpInput("1a2-3 ", totalPages = 9999))
    }

    @Test
    fun `caps the input to the digit count of a small range`() {
        assertEquals("30", coercePageJumpInput("300", totalPages = 30))
    }

    @Test
    fun `a very long topic accepts a five-digit page`() {
        assertEquals("15992", coercePageJumpInput("15992", totalPages = 15992))
    }

    @Test
    fun `a degenerate page count still allows at least one digit`() {
        assertEquals("7", coercePageJumpInput("77", totalPages = 0))
    }
}
