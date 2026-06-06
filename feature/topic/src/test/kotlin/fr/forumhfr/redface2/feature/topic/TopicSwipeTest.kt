package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicSwipeTest {

    @Test
    fun `forward swipe from a middle page targets the next page`() {
        assertEquals(3, swipeTargetPage(currentPage = 2, totalPages = 5, forward = true))
    }

    @Test
    fun `forward swipe from the last page is blocked`() {
        assertNull(swipeTargetPage(currentPage = 5, totalPages = 5, forward = true))
    }

    @Test
    fun `backward swipe from a middle page targets the previous page`() {
        assertEquals(1, swipeTargetPage(currentPage = 2, totalPages = 5, forward = false))
    }

    @Test
    fun `backward swipe from the first page is blocked`() {
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 5, forward = false))
    }

    @Test
    fun `single-page topic blocks both directions`() {
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 1, forward = true))
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 1, forward = false))
    }
}
