package fr.forumhfr.redface2.feature.settings

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu
import fr.forumhfr.redface2.core.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class ReportContentScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val intents = mutableListOf<ReportContentIntent>()
    private lateinit var hostView: View
    private lateinit var density: Density
    private var imeBottom = 0

    @Test
    fun loginActionIsVisibleWhenAnonymous() {
        mount(ReportContentUiState(session = ReportSession.ANONYMOUS))
        compose.onNodeWithText("Se connecter").performClick()
        compose.onNodeWithText("Écrire par email")
            .assertTextEquals("Écrire par email", "Ouvre l’application mail")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(ReportContentIntent.Login, ReportContentIntent.Email), intents)
    }

    @Test
    fun accountMenuReportActionDispatchesItsActualClick() {
        var reports = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                RedfaceAccountMenu(
                    authState = AuthState.Anonymous, versionName = "0.65.0", versionCode = 123,
                    onLogin = {}, onLogout = {}, onOpenDiagnostics = {}, onReportContent = { reports++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Ouvrir le menu compte").performClick()
        compose.onNodeWithText("Signaler un contenu").performClick()
        assertEquals(1, reports)
    }

    @Test
    fun validFieldsEnableSend() {
        mount(ready())
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsEnabled()
    }

    @Test
    fun invalidLinkDisablesSend() {
        mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, "Description", "mailto:mail@example.org")))
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun requiredFieldsGateSend() {
        mount(ready().copy(draft = ReportDraft()))
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Sécurité des enfants (CSAE/CSAM)").performScrollTo().performClick()
        assertEquals(listOf(ReportContentIntent.ReasonChanged(ReportReason.CHILD_SAFETY)), intents)
    }

    @Test
    fun sendingLocksControls() {
        mount(ready().copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, EditorSession("session", "tester", "report")),
        )))
        compose.onNodeWithText("Description du problème *").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Lien du contenu (facultatif)").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Autre").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun uncertaintyPreservesInputAndOffersMessageCheck() {
        mount(ready().copy(error = ReportError.UNCERTAIN))
        compose.onNodeWithText("Description privée").performScrollTo().assertExists()
        compose.onNodeWithText("Vérifier mes messages").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(ReportContentIntent.CheckMessages), intents)
    }

    @Test
    fun expiredSessionOffersLoginAlongsideTheUncertaintyWarning() {
        mount(ready().copy(
            sessionExpired = true, formAvailable = false, error = ReportError.UNCERTAIN,
            submission = EditorSubmissionState.Uncertain(null),
        ))
        compose.onNodeWithText(
            "Envoi non confirmé. Vérifiez vos messages avant de renvoyer. Votre saisie est conservée.",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("Se connecter").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Vérifier mes messages").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Description privée").performScrollTo().assertExists()
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(ReportContentIntent.Login, ReportContentIntent.CheckMessages), intents)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeKeyboardKeepsLinkVisibleAndSendReachable() {
        mount(ready())
        focusWithKeyboard("Lien du contenu (facultatif)")
        compose.onNodeWithText("Envoyer le signalement")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(intents.single() is ReportContentIntent.SubmitWithTimeout)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeKeyboardKeepsDescriptionVisible() {
        mount(ready())
        focusWithKeyboard("Description du problème *")
    }

    private fun ready() = ReportContentUiState(
        session = ReportSession.AUTHENTICATED, draftReady = true, formAvailable = true,
        draft = ReportDraft(ReportReason.OTHER, "Description privée"),
    )

    private fun mount(state: ReportContentUiState) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                val view = LocalView.current
                val currentDensity = LocalDensity.current
                val keyboardBottom = WindowInsets.ime.getBottom(currentDensity)
                SideEffect {
                    hostView = view
                    density = currentDensity
                    imeBottom = keyboardBottom
                }
                ReportContent(state, { intents += it })
            }
        }
    }

    private fun focusWithKeyboard(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        val keyboardHeight = with(density) { 220.dp.roundToPx() }
        // Exercise the real Compose inset listener; Robolectric does not run a keyboard service.
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.systemBars(), false)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(keyboardHeight, imeBottom) }
        compose.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
    }
}
