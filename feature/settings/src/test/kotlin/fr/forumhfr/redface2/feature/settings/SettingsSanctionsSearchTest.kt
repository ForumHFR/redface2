package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSanctionsSearchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `sanction keywords find the account history entry`() {
        lateinit var sections: List<SettingsCatalogueSection>
        compose.setContent { sections = catalogue(isAuthenticated = true) }
        compose.waitForIdle()
        listOf("Mes sanctions", "historique", "TT", "ban", "modération").forEach { query ->
            val matches = filterSettingsSections(sections.map { it.toSearchable() }, query).flatMap { it.items }
            assertTrue("query=$query", matches.any { it.id == "sanctions" && it.enabled })
        }
    }

    @Test
    fun `search opens sanctions directly when authenticated`() {
        var opens = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                catalogue(isAuthenticated = true, onOpenSanctions = { opens++ })
                    .flatMap { it.items }.single { it.searchable.id == "sanctions" }.render()
            }
        }
        compose.onNodeWithText("Mes sanctions").assertIsEnabled().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `anonymous entry remains searchable but disabled`() {
        lateinit var item: SettingsSearchableItem
        var opens = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                val sections = catalogue(isAuthenticated = false, onOpenSanctions = { opens++ })
                item = filterSettingsSections(sections.map { it.toSearchable() }, "sanctions")
                    .flatMap { it.items }.single { it.id == "sanctions" }
                sections.flatMap { it.items }.single { it.searchable.id == "sanctions" }.render()
            }
        }
        compose.waitForIdle()
        assertFalse(item.enabled)
        assertEquals("Connexion requise", item.description)
        compose.onNodeWithText("Mes sanctions").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Connexion requise").assertExists()
        assertEquals(0, opens)
    }

    @Composable
    private fun catalogue(
        isAuthenticated: Boolean,
        onOpenSanctions: () -> Unit = {},
    ): List<SettingsCatalogueSection> = buildSettingsCatalogue(
        state = SettingsState(),
        onIntent = {},
        startScreenState = StartScreenSettingsState(),
        onStartScreenIntent = {},
        onOpenProxy = {},
        onOpenMaintenance = {},
        onOpenDisplay = {},
        onOpenImages = {},
        onOpenAccountAbout = {},
        onOpenBlacklist = {},
        onOpenSanctions = onOpenSanctions,
        isAuthenticated = isAuthenticated,
    )
}
