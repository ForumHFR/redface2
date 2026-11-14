package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
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
import org.robolectric.annotation.GraphicsMode

/** ED-10/85: a close that cannot save never pops silently; the choice is explicit and accessible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorUnsavedCloseDialogTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show() = compose.setContent {
        RedfaceTheme { EditorUnsavedCloseDialog(onStay = { calls += "stay" }, onLeave = { calls += "leave" }) }
    }

    @Test
    fun `the dialog has an accessible name and announces the loss`() {
        show()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Brouillon non enregistré")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(
            "Le brouillon n’a pas pu être enregistré. Si vous quittez, les dernières modifications seront perdues.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Rester").assertHasClickAction()
        compose.onNodeWithText("Quitter sans enregistrer").assertHasClickAction()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test
    fun `Rester and Quitter sans enregistrer are two distinct explicit choices`() {
        show()
        compose.onNodeWithText("Rester").performClick()
        compose.onNodeWithText("Quitter sans enregistrer").performClick()
        compose.runOnIdle { assertEquals(listOf("stay", "leave"), calls) }
    }
}
