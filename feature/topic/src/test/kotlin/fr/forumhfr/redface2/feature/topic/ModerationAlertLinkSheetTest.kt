package fr.forumhfr.redface2.feature.topic

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ModerationAlertLinkSheetTest {
    @get:Rule
    val compose = createComposeRule()
    private val target = ModerationAlertLinkTarget(cat = 23, post = 35421, numreponse = 2_800_456, page = 76)

    @Test
    fun `info shows HFR text treatment date and the known topic above view post`() {
        val intents = mutableListOf<ModerationAlertLinkIntent>()
        mount(
            ModerationAlertLinkState.Info(target, "Texte HFR verbatim", "2026-09-05 17:27:28"),
            topicTitle = "Redface 2",
            onIntent = intents::add,
        )
        compose.onNodeWithText("Alerte modération").assertExists()
        compose.onNodeWithText("Texte HFR verbatim").assertExists()
        compose.onNodeWithText("Traitée le 2026-09-05 17:27:28").assertExists()
        val subtitle = compose.onNodeWithText("Redface 2, page 76").assertHasNoClickAction().fetchSemanticsNode()
        val button = compose.onNodeWithText("Voir le message").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(subtitle.boundsInRoot.bottom < button.boundsInRoot.top)
        assertEquals(listOf("Voir le message"), button.config[SemanticsProperties.Text].map { it.text })
        compose.onNodeWithText("Voir le message").performClick()
        compose.onNodeWithText("Fermer").performClick()
        assertEquals(listOf(ModerationAlertLinkIntent.ViewPost, ModerationAlertLinkIntent.Dismiss), intents)
        compose.onNodeWithText("Envoyer").assertDoesNotExist()
    }

    @Test
    fun `unknown title falls back to message topic and page identifiers`() {
        mount(ModerationAlertLinkState.Info(target, "Texte HFR"))
        compose.onNodeWithText(
            "Message n° 2800456 du sujet 35421, page 76", useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("Voir le message", substring = true).assertIsEnabled()
    }

    @Test
    fun `blank HFR message uses the existing generic fallback`() {
        mount(ModerationAlertLinkState.Info(target, " "))
        compose.onNodeWithText(
            "HFR ne permet pas de confirmer ce signalement. Fermez cette fenêtre et consultez à nouveau son état.",
        ).assertExists()
    }

    @Test
    fun `anonymous information keeps view post available`() {
        val intents = mutableListOf<ModerationAlertLinkIntent>()
        mount(ModerationAlertLinkState.SignInRequired(target), onIntent = intents::add)
        compose.onNodeWithText("Connectez-vous pour consulter cette alerte").assertExists()
        compose.onNodeWithText("Voir le message", substring = true).assertIsEnabled().performClick()
        assertEquals(listOf(ModerationAlertLinkIntent.ViewPost), intents)
    }

    @Test
    fun `error offers retry and view post`() {
        val intents = mutableListOf<ModerationAlertLinkIntent>()
        mount(ModerationAlertLinkState.Error(target, HfrErrorKind.Other), onIntent = intents::add)
        compose.onNodeWithText(
            "Impossible de contacter la modération. Vérifiez votre connexion et votre session HFR.",
        ).assertExists()
        compose.onNodeWithText("Réessayer").performClick()
        compose.onNodeWithText("Voir le message", substring = true).assertIsEnabled()
        assertEquals(listOf(ModerationAlertLinkIntent.Retry), intents)
    }

    @Test
    fun `reload in an open sheet is accessible and can be closed`() {
        val intents = mutableListOf<ModerationAlertLinkIntent>()
        mount(ModerationAlertLinkState.Loading(target, keepSheetOpen = true), onIntent = intents::add)
        compose.onNodeWithContentDescription("Signalement en cours de chargement ou d’envoi").assertExists()
        compose.onNodeWithText("Fermer").performClick()
        assertEquals(listOf(ModerationAlertLinkIntent.Dismiss), intents)
    }

    @Test
    fun `reload in an open sheet keeps the view-post button visible`() {
        val intents = mutableListOf<ModerationAlertLinkIntent>()
        mount(ModerationAlertLinkState.Loading(target, keepSheetOpen = true), onIntent = intents::add)
        compose.onNodeWithText("Voir le message", substring = true).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Fermer").assertIsDisplayed()
        assertEquals(listOf(ModerationAlertLinkIntent.ViewPost), intents)
    }

    @Test
    fun `initial loading does not compose a sheet or its actions`() {
        mount(ModerationAlertLinkState.Loading(target))
        compose.onNodeWithText("Alerte modération").assertDoesNotExist()
        compose.onNodeWithText("Voir le message").assertDoesNotExist()
        compose.onNodeWithText("Fermer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Signalement en cours de chargement ou d’envoi").assertDoesNotExist()
    }

    @Test
    fun `account reload immediately removes the previous message and date`() {
        val state = mutableStateOf<ModerationAlertLinkState>(
            ModerationAlertLinkState.Info(target, "Ancien compte", "2026-09-05 17:27:28"),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ModerationAlertLinkSheet(state.value, {}, "Redface 2")
            }
        }
        compose.onNodeWithText("Ancien compte").assertIsDisplayed()
        compose.runOnIdle { state.value = ModerationAlertLinkState.Loading(target, keepSheetOpen = true) }
        compose.onNodeWithText("Ancien compte").assertDoesNotExist()
        compose.onNodeWithText("Traitée le 2026-09-05 17:27:28").assertDoesNotExist()
        compose.onNodeWithContentDescription("Signalement en cours de chargement ou d’envoi").assertExists()
        compose.onNodeWithText("Fermer").assertIsDisplayed()
    }

    @Test
    fun `idle does not display a sheet`() {
        mount(ModerationAlertLinkState.Idle)
        compose.onNodeWithText("Alerte modération").assertDoesNotExist()
    }

    @Test
    fun `navigation closes the sheet`() {
        mount(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = true))
        compose.onNodeWithText("Alerte modération").assertDoesNotExist()
    }

    private fun mount(
        state: ModerationAlertLinkState,
        topicTitle: String? = null,
        onIntent: (ModerationAlertLinkIntent) -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ModerationAlertLinkSheet(state, onIntent, topicTitle)
            }
        }
    }
}
