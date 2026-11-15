package fr.forumhfr.redface2.feature.flags

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.domain.flags.FlagCategoryCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagsRefreshBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `partial warning stays visible with no displayed rows and offers retry`() {
        var retries = 0
        mount(state(FlagRefreshStatus.PARTIAL), onRetry = { retries++ })
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Liste partiellement actualisée", substring = true).assertExists()
        compose.onNodeWithText("Réessayer").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `failed empty cached list shows its date and retry without requiring a row`() {
        mount(state(FlagRefreshStatus.FAILED))
        compose.onNodeWithText("Actualisation impossible.", substring = true).assertExists()
        compose.onNodeWithText("Liste du", substring = true).assertExists()
        compose.onNodeWithText("Réessayer").assertExists()
    }

    @Test
    fun `session expiry uses the existing reconnect text and action`() {
        var logins = 0
        mount(state(FlagRefreshStatus.FAILED, FlagFailureKind.SESSION), onLogin = { logins++ })
        compose.onNodeWithText("Session HFR expirée", substring = true).assertExists()
        compose.onNodeWithText("Réessayer").assertDoesNotExist()
        compose.onNodeWithText("Se connecter à HFR").performClick()
        assertEquals(1, logins)
    }

    @Test
    fun `unrecognized schema has a separate label`() {
        mount(state(FlagRefreshStatus.FAILED, FlagFailureKind.SCHEMA))
        compose.onNodeWithText("Réponse HFR non reconnue", substring = true).assertExists()
        compose.onNodeWithText("Session HFR expirée", substring = true).assertDoesNotExist()
    }

    private fun mount(state: FlagsListUiState.Success, onRetry: () -> Unit = {}, onLogin: () -> Unit = {}) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagsRefreshBanner(state, onRetry, onLogin)
            }
        }
    }

    private fun state(status: FlagRefreshStatus, failure: FlagFailureKind = FlagFailureKind.NETWORK) =
        FlagsListUiState.Success(
            FlagsContent.Flat(emptyList()),
            coverage = FlagCoverage(listOf(FlagCategoryCoverage(13, FlagSource.BUCKET,
                Instant.parse("2026-05-03T10:00:00Z"), Instant.parse("2026-05-03T11:00:00Z"), failure))),
            refreshStatus = status,
            cachedAt = Instant.parse("2026-05-03T10:00:00Z"),
        )
}
