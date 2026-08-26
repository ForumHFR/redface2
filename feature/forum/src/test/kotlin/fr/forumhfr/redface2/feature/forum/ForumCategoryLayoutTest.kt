package fr.forumhfr.redface2.feature.forum

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1131 — pure contract of the category-list geometry helper. The FAB clearance is reserved as a
 * bottom inset ONLY when the create-topic FAB is rendered; otherwise the list gets no padding, so
 * an anonymous session (no FAB) shows no 88.dp void. All other sides stay at zero: the 88.dp is
 * exclusively the guard against the FAB, not a navigation-bar inset (that lives on the root Column).
 */
class ForumCategoryLayoutTest {

    @Test
    fun `reserves the FAB clearance at the bottom when the FAB is shown`() {
        val padding = forumListContentPadding(reserveFabSpace = true)
        assertEquals(88.dp, padding.calculateBottomPadding())
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `reserves nothing when the FAB is absent`() {
        val padding = forumListContentPadding(reserveFabSpace = false)
        assertEquals(0.dp, padding.calculateBottomPadding())
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
    }
}
