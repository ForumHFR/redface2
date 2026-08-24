package fr.forumhfr.redface2.feature.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1040 lot 6 — regression proof for the MP content/position mismatch window. */
class PrivateMessageListAlignmentTest {

    @Test
    fun `content swap blocks late settle and tap anchor until landing completes`() {
        val alignment = PrivateMessageListAlignment()
        alignment.onLandingApplied(page = 1)
        assertTrue(alignment.shouldPersist(canonicalPage = 1, isLoaded = true))

        // Content B is now canonical but the shared list still contains A's coordinates.
        assertFalse(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))

        alignment.onLandingApplied(page = 2)
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))
        assertEquals(2, alignment.alignedPage)
    }

    @Test
    fun `nothing persists before first landing or without loaded content`() {
        val alignment = PrivateMessageListAlignment()

        assertFalse(alignment.shouldPersist(canonicalPage = 1, isLoaded = true))
        alignment.onLandingApplied(page = 1)
        assertFalse(alignment.shouldPersist(canonicalPage = 1, isLoaded = false))
    }

    @Test
    fun `scrollbar and zoom producers are excluded from an otherwise aligned page`() {
        val alignment = PrivateMessageListAlignment().apply { onLandingApplied(page = 3) }

        assertFalse(
            shouldPersistPrivateMessageAnchor(
                alignment = alignment,
                canonicalPage = 3,
                isLoaded = true,
                isScrollbarDragging = true,
                isZoomPositionMutationInProgress = false,
            ),
        )
        assertFalse(
            shouldPersistPrivateMessageAnchor(
                alignment = alignment,
                canonicalPage = 3,
                isLoaded = true,
                isScrollbarDragging = false,
                isZoomPositionMutationInProgress = true,
            ),
        )
        assertTrue(
            shouldPersistPrivateMessageAnchor(
                alignment = alignment,
                canonicalPage = 3,
                isLoaded = true,
                isScrollbarDragging = false,
                isZoomPositionMutationInProgress = false,
            ),
        )
    }
}
