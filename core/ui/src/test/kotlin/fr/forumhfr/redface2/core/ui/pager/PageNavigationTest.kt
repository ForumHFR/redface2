package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins recovery navigation independently from page metadata populated by a successful load. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class PageNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `previous emits from a high current page when available pages are empty`() {
        val selectedPages = mutableListOf<Int>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 4,
                    availablePages = emptyList(),
                    canGoPrevious = true,
                    canGoNext = false,
                    onOpenPage = selectedPages::add,
                )
            }
        }

        compose.onNodeWithText("Précédent").performClick()

        assertEquals(listOf(3), selectedPages)
    }
}
