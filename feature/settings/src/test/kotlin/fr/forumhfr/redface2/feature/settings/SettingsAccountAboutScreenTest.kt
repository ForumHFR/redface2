package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class SettingsAccountAboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `authenticated account opens sanctions and retains future profile settings`() {
        var opens = 0
        mount(isAuthenticated = true, onOpenSanctions = { opens++ })
        compose.onNodeWithText("Mes sanctions")
            .assert(hasText("Historique des sanctions de votre compte HFR"))
            .assertIsEnabled()
            .performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("Historique des sanctions de votre compte HFR").assertExists()
        compose.onNodeWithText("Avatars, signatures, messages par page").assertHasNoClickAction()
        compose.onNodeWithText("Connexion requise").assertDoesNotExist()
    }

    @Test
    fun `anonymous account disables sanctions with a connection explanation`() {
        var opens = 0
        mount(isAuthenticated = false, onOpenSanctions = { opens++ })
        compose.onNodeWithText("Mes sanctions")
            .assert(hasText("Connexion requise"))
            .assertIsNotEnabled()
            .performClick()
        compose.onNodeWithText("Connexion requise").assertExists()
        assertEquals(0, opens)
    }

    @Test
    fun `sanctions precede the future profile section and its availability note`() {
        mount(isAuthenticated = true, onOpenSanctions = {})
        val labels = listOf(
            "Compte HFR",
            "Mes sanctions",
            "Réglages du profil HFR",
            "Avatars, signatures, messages par page",
            "Ces réglages viennent de votre profil HFR et arriveront plus tard.",
        )
        labels.zipWithNext().forEach { (before, after) ->
            val beforeBounds = compose.onNodeWithText(before).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val afterBounds = compose.onNodeWithText(after).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$before must precede $after", beforeBounds.bottom <= afterBounds.top)
        }
    }

    private fun mount(isAuthenticated: Boolean, onOpenSanctions: () -> Unit) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                SettingsAccountAboutScreen(
                    onBack = {},
                    versionName = "0.54.5",
                    versionCode = 1,
                    onOpenDiagnostics = {},
                    onReportContent = {},
                    isAuthenticated = isAuthenticated,
                    onOpenSanctions = onOpenSanctions,
                )
            }
        }
    }
}
