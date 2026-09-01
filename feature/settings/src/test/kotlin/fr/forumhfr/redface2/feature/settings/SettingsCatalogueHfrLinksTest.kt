package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isToggleable
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

/** #1032/#1184/#1207 — HFR-link status, chooser preference and Firefox help catalogue contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueHfrLinksTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the row lives in the network section`() {
        val network = buildCatalogue(HfrLinkHandlingStatus.UNKNOWN).first { it.id == "network" }

        assertTrue(network.items.any { it.searchable.id == "hfr_link_default_app" })
        assertTrue(network.items.any { it.searchable.id == "always_ask_link_app" })
        assertTrue(network.items.any { it.searchable.id == "hfr_link_firefox_help" })
        val ids = network.items.map { it.searchable.id }
        assertEquals(ids.indexOf("hfr_link_default_app") + 1, ids.indexOf("always_ask_link_app"))
        assertEquals(ids.indexOf("always_ask_link_app") + 1, ids.indexOf("hfr_link_firefox_help"))
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
            listOf("hfr_link_default_app", "always_ask_link_app", "hfr_link_firefox_help"),
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

    @Test
    fun `tapping always ask dispatches the flipped preference`() {
        val received = mutableListOf<SettingsIntent>()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(
                    status = HfrLinkHandlingStatus.NOT_DEFAULT,
                    onOpen = {},
                    onIntent = received::add,
                ).first { it.id == "network" }
                    .items
                    .first { it.searchable.id == "always_ask_link_app" }
                    .render()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.AlwaysAskLinkAppChanged(true)), received)
    }

    @Test
    fun `the Firefox workaround is displayed below the link setting`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(HfrLinkHandlingStatus.NOT_DEFAULT, onOpen = {})
                    .first { it.id == "network" }
                    .items
                    .first { it.searchable.id == "hfr_link_firefox_help" }
                    .render()
            }
        }

        composeTestRule.onNodeWithText(
            "Sous Firefox, réglez « Ouvrir les liens dans les applications » sur « Ne jamais » " +
                "pour lire dans le navigateur, ou activez « Toujours demander quelle app » " +
                "ci-dessus pour choisir l’application à chaque fois.",
        ).assertExists()
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
        onIntent: (SettingsIntent) -> Unit = {},
    ): List<SettingsCatalogueSection> = buildSettingsCatalogue(
        state = SettingsState(),
        onIntent = onIntent,
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
