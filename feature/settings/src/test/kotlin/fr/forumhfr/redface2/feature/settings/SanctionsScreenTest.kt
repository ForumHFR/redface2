package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.profile.Sanction
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class SanctionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `loaded history displays the account and all sanction details`() {
        mount(SanctionsUiState.Loaded("XaTriX", listOf(sanction())))
        listOf(
            "Mes sanctions",
            "XaTriX",
            "Teletubbies",
            "Catégorie : Intelligence Artificielle",
            "Levée",
            "Du 13-06-2026 à 22:13 au 18-06-2026 à 22:13",
            "Par TotalRecall",
            "Promo de juin : pour deux TALC, un TT offert.",
        ).forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
        compose.onNodeWithText("En cours").assertDoesNotExist()
    }

    @Test
    fun `ongoing sanction displays since and does not invent a lifted date`() {
        mount(SanctionsUiState.Loaded("XaTriX", listOf(sanction().copy(liftedAt = null, reason = ""))))
        compose.onNodeWithText("En cours").assertIsDisplayed()
        compose.onNodeWithText("Depuis 13-06-2026 à 22:13").assertIsDisplayed()
        compose.onNodeWithText("Levée").assertDoesNotExist()
        compose.onNodeWithText("Promo de juin : pour deux TALC, un TT offert.").assertDoesNotExist()
    }

    @Test
    fun `empty history displays its account and empty message`() {
        mount(SanctionsUiState.Empty("XaTelitte"))
        compose.onNodeWithText("XaTelitte").assertIsDisplayed()
        compose.onNodeWithText("Aucune sanction sur ce compte.").assertIsDisplayed()
    }

    @Test
    fun `anonymous history explains the required connection`() {
        mount(SanctionsUiState.SignInRequired)
        compose.onNodeWithText("Connexion requise").assertIsDisplayed()
        compose.onNodeWithText("Aucune sanction sur ce compte.").assertDoesNotExist()
    }

    @Test
    fun `error displays retry and dispatches the retry intent`() {
        val intents = mutableListOf<SanctionsIntent>()
        mount(SanctionsUiState.Error(HfrErrorKind.Network), onIntent = intents::add)
        compose.onNodeWithText("Pas de connexion").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()
        assertEquals(listOf(SanctionsIntent.Retry), intents)
    }

    @Test
    fun `back dispatches the navigation callback`() {
        var backs = 0
        mount(SanctionsUiState.Empty("XaTelitte"), onBack = { backs++ })
        compose.onNodeWithContentDescription("Retour").performClick()
        assertEquals(1, backs)
    }

    private fun mount(
        state: SanctionsUiState,
        onIntent: (SanctionsIntent) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                SanctionsContent(state = state, onIntent = onIntent, onBack = onBack)
            }
        }
    }

    private fun sanction(): Sanction = Sanction(
        pseudo = "XaTriX",
        kind = "Teletubbies",
        moderator = "TotalRecall",
        category = "Intelligence Artificielle",
        issuedAt = "13-06-2026 à 22:13",
        liftedAt = "18-06-2026 à 22:13",
        reason = "Promo de juin : pour deux TALC, un TT offert.",
    )
}
