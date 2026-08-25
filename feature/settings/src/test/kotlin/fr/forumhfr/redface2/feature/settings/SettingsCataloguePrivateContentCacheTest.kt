package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCataloguePrivateContentCacheTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `global cache row is in private messages and names every account`() {
        val mp = buildCatalogue().first { it.id == "mp" }
        val row = mp.items.first { it.searchable.id == "private_message_content_cache" }

        assertEquals(TITLE, row.searchable.title)
        assertEquals(DESCRIPTION, row.searchable.description)
        assertEquals(
            mp.items.indexOfFirst { it.searchable.id == "mp_unread_badge" } + 1,
            mp.items.indexOf(row),
        )
    }

    @Test
    fun `switch exposes role and full privacy description then requests confirmation`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(
            state = SettingsState(privateMessageContentCacheEnabled = true),
            onIntent = received::add,
        )

        composeTestRule.onNode(isToggleable())
            .assert(hasContentDescription("$TITLE. $DESCRIPTION"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.PrivateMessageContentCacheChanged(false)),
            received,
        )
    }

    @Test
    fun `confirmation action is an accessible button and dispatches destructive intent`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(
            state = SettingsState(
                privateMessageContentCacheEnabled = true,
                showDisablePrivateMessageContentCacheConfirm = true,
            ),
            onIntent = received::add,
        )

        composeTestRule.onNodeWithText(CONFIRM_ACTION)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.DisablePrivateMessageContentCacheConfirmed),
            received,
        )
    }

    @Test
    fun `failed purge explains the lock and exposes an accessible retry`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(
            state = SettingsState(
                privateMessageContentCachePurgePending = true,
                privateMessageContentCachePurgeError = true,
            ),
            onIntent = received::add,
        )

        composeTestRule.onNodeWithText(PURGE_ERROR).assertExists()
        composeTestRule.onNodeWithText(RETRY_ACTION)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.RetryPrivateMessageContentCachePurge),
            received,
        )
    }

    private fun buildCatalogue(): List<SettingsCatalogueSection> {
        lateinit var sections: List<SettingsCatalogueSection>
        composeTestRule.setContent { sections = testCatalogue(state = SettingsState(), onIntent = {}) }
        composeTestRule.waitForIdle()
        return sections
    }

    private fun mountRow(
        state: SettingsState,
        onIntent: (SettingsIntent) -> Unit,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(state, onIntent)
                    .first { it.id == "mp" }
                    .items
                    .first { it.searchable.id == "private_message_content_cache" }
                    .render()
            }
        }
        composeTestRule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun testCatalogue(
        state: SettingsState,
        onIntent: (SettingsIntent) -> Unit,
    ): List<SettingsCatalogueSection> = buildSettingsCatalogue(
        state = state,
        onIntent = onIntent,
        startScreenState = StartScreenSettingsState(),
        onStartScreenIntent = {},
        onOpenProxy = {},
        onOpenMaintenance = {},
        onOpenDisplay = {},
        onOpenImages = {},
        onOpenAccountAbout = {},
        onOpenBlacklist = {},
    )

    private companion object {
        const val TITLE = "Cache disque des messages privés (tous les comptes)"
        const val DESCRIPTION = "Activé pour tous les comptes utilisés sur cet appareil. " +
            "Conserve jusqu'à 5 pages par compte ; chaque page est revalidée par le réseau, " +
            "sans mode hors ligne."
        const val CONFIRM_ACTION = "Désactiver et supprimer"
        const val PURGE_ERROR = "Le cache reste désactivé, mais la suppression n'a pas abouti. " +
            "Les lectures et écritures disque restent bloquées."
        const val RETRY_ACTION = "Réessayer la suppression"
    }
}
