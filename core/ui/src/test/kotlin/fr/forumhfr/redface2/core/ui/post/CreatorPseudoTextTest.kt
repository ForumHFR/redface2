package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #221 — render contract of the shared leaf that applies `rememberCreatorPseudoBrush` to a pseudo. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorPseudoTextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `creator pseudo renders through the shared gold-sheen leaf`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CreatorPseudoText(author = "XaTriX")
            }
        }

        composeTestRule.onNodeWithTag(CREATOR_PSEUDO_TEXT_TAG).assertIsDisplayed()
        val pseudo = composeTestRule.onNodeWithText("XaTriX").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        val readLayout = requireNotNull(
            pseudo.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue("the Text layout must be readable", readLayout(layouts))
        assertNotNull(
            "the shared creator leaf must apply the gold-sheen brush",
            layouts.single().layoutInput.style.brush,
        )
    }
}
