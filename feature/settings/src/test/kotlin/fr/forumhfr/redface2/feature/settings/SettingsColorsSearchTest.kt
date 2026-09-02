package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #595 — search keywords exposed by the Affichage nav row must route to the colours sub-page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsColorsSearchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `colour keywords find the display row through the real catalogue`() {
        val searchable = buildCatalogue().map { it.toSearchable() }
        val queries = listOf("couleurs", "accent", "hexa", "Material You", "AMOLED", "fond", "blanc", "gris")

        queries.forEach { query ->
            val resultIds = filterSettingsSections(searchable, query)
                .flatMap { it.items }
                .map { it.id }

            assertTrue("query=$query", "display_nav" in resultIds)
        }
    }

    private fun buildCatalogue(): List<SettingsCatalogueSection> {
        lateinit var sections: List<SettingsCatalogueSection>
        composeTestRule.setContent { sections = testCatalogue() }
        composeTestRule.waitForIdle()
        return sections
    }

    @Composable
    private fun testCatalogue(): List<SettingsCatalogueSection> = buildSettingsCatalogue(
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
    )
}
