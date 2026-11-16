package fr.forumhfr.redface2.feature.flags

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.domain.flags.FlagCategoryCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
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
    fun `global marker never masks schema or transport failures - P2c fix`() {
        mount(withGlobalMarker(state(FlagRefreshStatus.PARTIAL, FlagFailureKind.SCHEMA)))
        compose.onNodeWithText("Réponse HFR non reconnue", substring = true).assertExists()
        compose.onNodeWithText("Couverture limitée", substring = true).assertDoesNotExist()
    }

    @Test
    fun `partial transport error takes priority over limited global coverage - P2c fix`() {
        mount(withGlobalMarker(state(FlagRefreshStatus.PARTIAL)))
        compose.onNodeWithText("Liste partiellement actualisée : 1 catégorie non actualisée.").assertExists()
        compose.onNodeWithText("Couverture limitée", substring = true).assertDoesNotExist()
    }

    @Test
    fun `targeted lookup banner counts unverified categories instead of claiming zero missing - P2c fix`() {
        val initial = state(FlagRefreshStatus.PARTIAL)
        mount(initial.copy(coverage = FlagCoverage(initial.coverage.categories.map {
            it.copy(lastSuccessAt = null, failure = null)
        })))
        compose.onNodeWithText("Liste partiellement actualisée : 1 catégorie non actualisée.").assertExists()
    }

    private fun withGlobalMarker(state: FlagsListUiState.Success) = state.copy(
        coverage = FlagCoverage(state.coverage.categories + FlagCategoryCoverage(
            0, FlagSource.GLOBAL, Instant.parse("2026-05-03T10:00:00Z"), Instant.parse("2026-05-03T10:00:00Z"),
        )),
    )

    @Test
    fun `missing sticky categories are named with singular wording in the banner - 984`() {
        mount(state(FlagRefreshStatus.PARTIAL).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(13, "Discussions")),
        )))
        compose.onNodeWithText("Épinglés non chargés pour la catégorie Discussions.").assertExists()
    }

    @Test
    fun `missing sticky categories use plural names and a bounded list - 984`() {
        mount(state(FlagRefreshStatus.PARTIAL).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(13, "Discussions"),
                FlagRefreshCategory(23, "Technologies Mobiles"), FlagRefreshCategory(32, "IA")),
        )))
        compose.onNodeWithText("Épinglés non chargés pour les catégories Discussions, Technologies Mobiles +1.")
            .assertExists()
    }

    @Test
    fun `missing sticky name after a disk reload uses an unnamed singular message - 984`() {
        mount(state(FlagRefreshStatus.PARTIAL).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(23, null)),
        )))
        compose.onNodeWithText("Épinglés non chargés pour 1 catégorie.").assertExists()
        compose.onNodeWithText("Catégorie 23", substring = true).assertDoesNotExist()
    }

    @Test
    fun `blank or missing sticky names use an unnamed plural message - 984`() {
        mount(state(FlagRefreshStatus.PARTIAL).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(13, "Discussions"),
                FlagRefreshCategory(23, " "), FlagRefreshCategory(32, null)),
        )))
        compose.onNodeWithText("Épinglés non chargés pour 3 catégories.").assertExists()
        compose.onNodeWithText("Catégorie 23", substring = true).assertDoesNotExist()
    }

    @Test
    fun `partial session expiry hides the redundant sticky message - 984`() {
        mount(state(FlagRefreshStatus.PARTIAL, FlagFailureKind.SESSION).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(13, "Discussions")),
        )))
        compose.onNodeWithText("Session HFR expirée", substring = true).assertExists()
        compose.onNodeWithText("Épinglés non chargés", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Se connecter à HFR").assertExists()
    }

    @Test
    fun `failed refresh hides the redundant sticky message - 984`() {
        mount(state(FlagRefreshStatus.FAILED).copy(refreshIssue = FlagRefreshIssue(
            stickyCategories = listOf(FlagRefreshCategory(13, "Discussions")),
        )))
        compose.onNodeWithText("Actualisation impossible.", substring = true).assertExists()
        compose.onNodeWithText("Épinglés non chargés", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Réessayer").assertExists()
    }

    @Test
    fun `unverified migrated snapshot has no failure banner`() {
        mount(state(FlagRefreshStatus.UNVERIFIED).copy(coverage = FlagCoverage()))
        compose.onNodeWithText("Actualisation impossible.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Réessayer").assertDoesNotExist()
    }

    @Test
    fun `one failed category uses the singular form`() {
        mount(state(FlagRefreshStatus.PARTIAL))
        compose.onNodeWithText("Liste partiellement actualisée : 1 catégorie non actualisée.").assertExists()
    }

    @Test
    fun `multiple failed categories use the plural form`() {
        val initial = state(FlagRefreshStatus.PARTIAL)
        val failed = initial.coverage.categories.single()
        mount(initial.copy(coverage = FlagCoverage(listOf(failed, failed.copy(cat = 23)))))
        compose.onNodeWithText("Liste partiellement actualisée : 2 catégories non actualisées.").assertExists()
    }

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
