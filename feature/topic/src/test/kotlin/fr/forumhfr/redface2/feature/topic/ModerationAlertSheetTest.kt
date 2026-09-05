package fr.forumhfr.redface2.feature.topic

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
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
class ModerationAlertSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `form focuses reason and enables send only for nonblank input`() {
        val state = mutableStateOf(ModerationAlertUi.Form(ModerationAlertState.Form("modo.php", "token", null)))
        val intents = mutableListOf<TopicIntent>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ModerationAlertSheet(state.value, onIntent = { intent ->
                    intents += intent
                    if (intent is TopicIntent.UpdateModerationReason) {
                        state.value = state.value.copy(reasonDraft = intent.reason)
                    }
                })
            }
        }
        compose.onNodeWithText("Raison").assertIsFocused()
        compose.onNodeWithText("Envoyer").assertIsNotEnabled()
        compose.onNodeWithText("Raison").performTextReplacement(" \n ")
        compose.onNodeWithText("Envoyer").assertIsNotEnabled()
        compose.onNodeWithText("Raison").performTextReplacement("Insultes")
        compose.onNodeWithText("Envoyer").assertIsEnabled().performClick()
        assertEquals(TopicIntent.SubmitModerationAlert, intents.last())
    }

    @Test
    fun `join prompt offers join and cancel without reason`() {
        val intents = mutableListOf<TopicIntent>()
        mount(ModerationAlertUi.JoinPrompt(ModerationAlertState.JoinPrompt("modo.php", "token", null)), intents::add)
        compose.onNodeWithText("Raison").assertDoesNotExist()
        compose.onNodeWithText("Me joindre").performClick()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(listOf(TopicIntent.JoinModerationAlert, TopicIntent.DismissModerationAlert), intents)
    }

    @Test
    fun `treated information shows HFR's own sentence, the date and only a close action`() {
        mount(
            ModerationAlertUi.Info(
                "Votre demande de modération sur ce message a été traitée le 2026-09-05 17:27:28",
                "2026-09-05 17:27:28",
            ),
        )
        // #293 — the body is HFR's text verbatim, not one of our strings.
        compose.onNodeWithText(
            "Votre demande de modération sur ce message a été traitée le 2026-09-05 17:27:28",
        ).assertExists()
        compose.onNodeWithText("Traitée le 2026-09-05 17:27:28").assertExists()
        compose.onNodeWithText("Fermer").assertIsEnabled()
        compose.onNodeWithText("Envoyer").assertDoesNotExist()
    }

    @Test
    fun `information falls back to the generic string when HFR says nothing`() {
        mount(ModerationAlertUi.Info(""))
        compose.onNodeWithText(
            "HFR ne permet pas de confirmer ce signalement. Fermez cette fenêtre et consultez à nouveau son état.",
        ).assertExists()
    }

    @Test
    fun `result shows HFR's own sentence verbatim`() {
        mount(
            ModerationAlertUi.Result(
                ModerationAlertOutcome.Sent("Un message a été envoyé avec succès aux modérateurs"),
            ),
        )
        compose.onNodeWithText("Un message a été envoyé avec succès aux modérateurs").assertExists()
    }

    @Test
    fun `loading is accessible and cancellable`() {
        val intents = mutableListOf<TopicIntent>()
        mount(ModerationAlertUi.Loading, intents::add)
        compose.onNodeWithContentDescription("Signalement en cours de chargement ou d’envoi").assertExists()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(listOf(TopicIntent.DismissModerationAlert), intents)
    }

    @Test
    fun `submission disables the reason and send action`() {
        mount(
            ModerationAlertUi.Form(
                ModerationAlertState.Form("modo.php", "token", null), reasonDraft = "Insultes", submitting = true,
            ),
        )
        compose.onNodeWithText("Raison").assertIsNotEnabled()
        compose.onNodeWithText("Envoyer").assertIsNotEnabled()
    }

    @Test
    fun `topic snackbar remains visible above the modal after a network error`() {
        compose.setContent {
            val snackbar = remember { SnackbarHostState() }
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ModerationAlertSheet(
                    state = ModerationAlertUi.Form(ModerationAlertState.Form("modo.php", "token", null)),
                    onIntent = {},
                    snackbarHostState = snackbar,
                )
            }
            LaunchedEffect(Unit) { snackbar.showSnackbar("Pas de connexion") }
        }
        compose.onNodeWithText("Pas de connexion").assertExists()
    }

    private fun mount(state: ModerationAlertUi, onIntent: (TopicIntent) -> Unit = {}) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ModerationAlertSheet(state, onIntent)
            }
        }
    }
}
