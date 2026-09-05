package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class SettingsAccountAboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `authenticated account opens sanctions and retains future profile settings`() {
        var opens = 0
        mount(isAuthenticated = true, onOpenSanctions = { opens++ })
        compose.onNodeWithText("Mes sanctions").assertIsEnabled().performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("Historique des sanctions de votre compte HFR").assertExists()
        compose.onNodeWithText("Avatars, signatures, messages par page").assertExists()
        compose.onNodeWithText("Connexion requise").assertDoesNotExist()
    }

    @Test
    fun `anonymous account disables sanctions with a connection explanation`() {
        var opens = 0
        mount(isAuthenticated = false, onOpenSanctions = { opens++ })
        compose.onNodeWithText("Mes sanctions").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Connexion requise").assertExists()
        assertEquals(0, opens)
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
