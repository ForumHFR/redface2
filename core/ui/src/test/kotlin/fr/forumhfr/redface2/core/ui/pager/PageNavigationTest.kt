package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins recovery navigation and the explicit page-picker shortcut contracts. */
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

    @Test
    fun `last extreme emits the known total page`() {
        val selectedPages = mutableListOf<Int>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 4,
                    availablePages = (1..12).toList(),
                    canGoPrevious = true,
                    canGoNext = true,
                    actions = PageNavigationActions.Extremes,
                    onOpenPage = selectedPages::add,
                )
            }
        }

        compose.onNodeWithContentDescription("Aller à la dernière page").performClick()

        assertEquals(listOf(12), selectedPages)
    }

    @Test
    fun `last extreme is disabled when the total is unknown`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 4,
                    availablePages = emptyList(),
                    canGoPrevious = true,
                    canGoNext = false,
                    actions = PageNavigationActions.Extremes,
                    onOpenPage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Aller à la dernière page").assertIsNotEnabled()
    }

    @Test
    fun `first extreme emits page one`() {
        val selectedPages = mutableListOf<Int>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 4,
                    availablePages = (1..12).toList(),
                    canGoPrevious = true,
                    canGoNext = true,
                    actions = PageNavigationActions.Extremes,
                    onOpenPage = selectedPages::add,
                )
            }
        }

        compose.onNodeWithContentDescription("Aller à la première page").performClick()

        assertEquals(listOf(1), selectedPages)
    }

    @Test
    fun `first extreme is disabled on page one`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 1,
                    availablePages = (1..12).toList(),
                    canGoPrevious = false,
                    canGoNext = true,
                    actions = PageNavigationActions.Extremes,
                    onOpenPage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Aller à la première page").assertIsNotEnabled()
    }
}
