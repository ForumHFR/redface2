package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.browser.HfrLinkHandlingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1032 — placement, dynamic French copy, search indexing and tap dispatch of the HFR-links row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueHfrLinksTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the row lives in the network section`() {
        val network = buildCatalogue(HfrLinkHandlingStatus.UNKNOWN).first { it.id == "network" }

        assertTrue(network.items.any { it.searchable.id == "hfr_link_default_app" })
    }

    @Test
    fun `the description reflects each default-handler status`() {
        // A single setContent (it may only be called once per test) resolves all three variants.
        lateinit var default: String
        lateinit var notDefault: String
        lateinit var unknown: String
        composeTestRule.setContent {
            default = hfrRowDescription(testCatalogue(HfrLinkHandlingStatus.DEFAULT_HANDLER, onOpen = {}))
            notDefault = hfrRowDescription(testCatalogue(HfrLinkHandlingStatus.NOT_DEFAULT, onOpen = {}))
            unknown = hfrRowDescription(testCatalogue(HfrLinkHandlingStatus.UNKNOWN, onOpen = {}))
        }
        composeTestRule.waitForIdle()

        assertEquals("Redface 2 est l'app par défaut pour forum.hardware.fr", default)
        assertEquals("Redface 2 n'est pas l'app par défaut — appuyez pour régler", notDefault)
        assertEquals("Gérer l'ouverture des liens dans les réglages Android", unknown)
    }

    @Test
    fun `the row is reachable by a network keyword search`() {
        val catalogue = buildCatalogue(HfrLinkHandlingStatus.NOT_DEFAULT)

        assertEquals(
            listOf("hfr_link_default_app"),
            filterSettingsSections(catalogue.map { it.toSearchable() }, "navigateur")
                .flatMap { it.items }
                .map { it.id },
        )
    }

    @Test
    fun `tapping the row dispatches the open-settings callback`() {
        var opened = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(HfrLinkHandlingStatus.NOT_DEFAULT, onOpen = { opened++ })
                    .first { it.id == "network" }
                    .items
                    .first { it.searchable.id == "hfr_link_default_app" }
                    .render()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Ouverture des liens HFR").performClick()

        assertEquals(1, opened)
    }

    private fun hfrRowDescription(sections: List<SettingsCatalogueSection>): String = requireNotNull(
        sections.first { it.id == "network" }
            .items
            .first { it.searchable.id == "hfr_link_default_app" }
            .searchable
            .description,
    )

    private fun buildCatalogue(status: HfrLinkHandlingStatus): List<SettingsCatalogueSection> {
        lateinit var sections: List<SettingsCatalogueSection>
        composeTestRule.setContent { sections = testCatalogue(status, onOpen = {}) }
        composeTestRule.waitForIdle()
        return sections
    }

    @Composable
    private fun testCatalogue(
        status: HfrLinkHandlingStatus,
        onOpen: () -> Unit,
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
        hfrLinkStatus = status,
        onOpenHfrLinkSettings = onOpen,
    )
}
