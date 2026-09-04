package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #326 — launcher-icon keywords route to the dedicated Affichage catalogue entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLauncherIconSearchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `launcher icon keywords find the dedicated catalogue row`() {
        val searchable = buildCatalogue().map { it.toSearchable() }

        listOf("icône", "lanceur", "launcher", "logo", "application").forEach { query ->
            val resultIds = filterSettingsSections(searchable, query)
                .flatMap { it.items }
                .map { it.id }

            assertTrue("query=$query", "display_launcher_icon" in resultIds)
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
