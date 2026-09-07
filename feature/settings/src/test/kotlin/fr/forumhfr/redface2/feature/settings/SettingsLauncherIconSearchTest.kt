package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #326 — launcher-icon keywords route to the dedicated gallery catalogue entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLauncherIconSearchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var galleryOpens = 0
    private var displayOpens = 0

    @Test
    fun `launcher icon keywords find the dedicated catalogue row`() {
        val searchable = buildCatalogue().map { it.toSearchable() }

        listOf("icône", "lanceur", "launcher", "logo", "application", "RF1").forEach { query ->
            val resultIds = filterSettingsSections(searchable, query)
                .flatMap { it.items }
                .map { it.id }

            assertTrue("query=$query", "display_launcher_icon" in resultIds)
        }
    }

    @Test
    fun `search result opens the icon gallery directly`() {
        composeTestRule.setContent {
            testCatalogue().flatMap { it.items }.single { it.searchable.id == "display_launcher_icon" }.render()
        }

        composeTestRule.onNodeWithText("Icône de l'application").performClick()

        assertEquals(1, galleryOpens)
        assertEquals(0, displayOpens)
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
        onOpenDisplay = { displayOpens++ },
        onOpenAppIcon = { galleryOpens++ },
        onOpenImages = {},
        onOpenAccountAbout = {},
        onOpenBlacklist = {},
    )
}
