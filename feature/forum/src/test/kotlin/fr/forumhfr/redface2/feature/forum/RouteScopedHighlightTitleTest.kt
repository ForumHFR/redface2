package fr.forumhfr.redface2.feature.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteScopedHighlightTitleTest {

    @Test
    fun `keeps highlight on initial listing`() {
        val request = request(initialSubcat = 550, initialPage = 1)

        assertEquals(
            "Sujet créé",
            routeScopedHighlightTitle(request, selectedSubcat = 550, page = 1),
        )
    }

    @Test
    fun `drops highlight after page change`() {
        val request = request(initialSubcat = 550, initialPage = 1)

        assertNull(routeScopedHighlightTitle(request, selectedSubcat = 550, page = 2))
    }

    @Test
    fun `drops highlight after subcategory change`() {
        val request = request(initialSubcat = 550, initialPage = 1)

        assertNull(routeScopedHighlightTitle(request, selectedSubcat = 551, page = 1))
    }

    @Test
    fun `normalizes non-positive initial page to first page`() {
        val request = request(initialSubcat = null, initialPage = 0)

        assertEquals(
            "Sujet créé",
            routeScopedHighlightTitle(request, selectedSubcat = null, page = 1),
        )
        assertNull(routeScopedHighlightTitle(request, selectedSubcat = null, page = 2))
    }

    private fun request(
        initialSubcat: Int?,
        initialPage: Int,
    ): CategoryRequest =
        CategoryRequest(
            cat = 23,
            initialSubcat = initialSubcat,
            initialPage = initialPage,
            highlightTitle = "Sujet créé",
        )
}
