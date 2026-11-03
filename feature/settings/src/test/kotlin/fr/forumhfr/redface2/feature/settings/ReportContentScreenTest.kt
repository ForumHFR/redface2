package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu
import fr.forumhfr.redface2.core.model.AuthState
import org.junit.Assert.assertEquals
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

    @Test
    fun loginActionIsVisibleWhenAnonymous() {
        mount(ReportContentUiState(session = ReportSession.ANONYMOUS))
        compose.onNodeWithText("Se connecter").performClick()
        compose.onNodeWithText("Écrire par email").performClick()
        compose.onNodeWithText("Ouvre l’application mail").assertExists()
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
        compose.onNodeWithText("Envoyer le signalement").assertIsEnabled()
    }

    @Test
    fun invalidLinkDisablesSend() {
        mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, "Description", "mailto:mail@example.org")))
        compose.onNodeWithText("Envoyer le signalement").assertIsNotEnabled()
    }

    @Test
    fun requiredFieldsGateSend() {
        mount(ready().copy(draft = ReportDraft()))
        compose.onNodeWithText("Envoyer le signalement").assertIsNotEnabled()
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

    private fun ready() = ReportContentUiState(
        session = ReportSession.AUTHENTICATED, draftReady = true, formAvailable = true,
        draft = ReportDraft(ReportReason.OTHER, "Description privée"),
    )

    private fun mount(state: ReportContentUiState) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                ReportContent(state, { intents += it })
            }
        }
    }
}
