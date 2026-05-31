package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Test

class PageJumpInputTest {

    @Test
    fun `strips non-digit characters from the raw input`() {
        // totalPages = 9999 → 4-digit room, so "123" is not clipped; only the non-digits go.
        assertEquals("123", coercePageJumpInput("1a2-3 ", totalPages = 9999))
    }

    @Test
    fun `caps the input to the digit count of a small topic`() {
        // 30 pages → 2 digits: a third digit is dropped (page 300 never exists anyway).
        assertEquals("30", coercePageJumpInput("300", totalPages = 30))
    }

    @Test
    fun `#235 — a very long topic accepts a 5-digit page (Ukraine, 15992 pages)`() {
        // The old fixed 4-digit cap truncated this to "1599", making page 15992 untypable.
        assertEquals("15992", coercePageJumpInput("15992", totalPages = 15992))
    }

    @Test
    fun `degenerate non-positive totalPages still allows at least one digit`() {
        assertEquals("7", coercePageJumpInput("77", totalPages = 0))
    }
}
