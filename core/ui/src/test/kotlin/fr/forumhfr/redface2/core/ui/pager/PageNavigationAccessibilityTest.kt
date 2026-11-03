package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1448 — extreme-page buttons use their visible labels as their only accessible names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class PageNavigationAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `first extreme exposes only its visible label and retains button semantics`() {
        mount()

        compose.onNodeWithText("Première page", useUnmergedTree = false)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assertTextEquals("Première page")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
            .assertHasClickAction()
    }

    @Test
    fun `last extreme exposes only its visible label and retains button semantics`() {
        mount()

        compose.onNodeWithText("Dernière page", useUnmergedTree = false)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assertTextEquals("Dernière page")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
            .assertHasClickAction()
    }

    private fun mount() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageNavigation(
                    currentPage = 4,
                    availablePages = (1..12).toList(),
                    canGoPrevious = true,
                    canGoNext = true,
                    actions = PageNavigationActions.Extremes,
                    onOpenPage = {},
                )
            }
        }
    }
}
