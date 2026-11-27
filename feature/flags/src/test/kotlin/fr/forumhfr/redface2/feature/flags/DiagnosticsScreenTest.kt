package fr.forumhfr.redface2.feature.flags

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.forumhfr.redface2.core.domain.diagnostics.CrashReportStack
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeState
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeStatus
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val EDITOR = "fr.forumhfr.redface2.feature.editor.Editor"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val viewModels = ViewModelStore()

    @After
    fun clearViewModels() = viewModels.clear()

    @Test
    fun `copy includes the structured crash and clear removes it from the screen and the next export`() {
        val log = DiagnosticsLog()
        val error = IllegalStateException("Editor failed at https://forum.hardware.fr/mp/654321?hash_check=SecretHash")
            .apply { stackTrace = arrayOf(StackTraceElement(EDITOR, "open", "Cookie.kt", 19)) }
        val details = buildString {
            CrashReportStack.write(Thread.currentThread(), Thread.currentThread(), error) {
                appendLine(it)
                true
            }
        }
        log.recordCrash(0, details)
        show(log)

        compose.onNodeWithText("pile du dernier plantage", substring = true).assertIsDisplayed()
        compose.onNodeWithText(DiagnosticsLog.CRASH_TAG).assertIsDisplayed()
        compose.onNodeWithText("Copier").performClick()
        val exportedCrash = clipboardText()
        assertTrue(exportedCrash.contains("java.lang.IllegalStateException: message omitted"))
        assertTrue(exportedCrash.contains("at $EDITOR.open(Cookie.kt:19)"))
        assertFalse(exportedCrash.contains("SecretHash"))

        compose.onNodeWithText("Vider").performClick()
        compose.onNodeWithText(DiagnosticsLog.CRASH_TAG).assertDoesNotExist()
        compose.onNodeWithText("Copier").assertIsNotEnabled()

        log.record(DiagnosticsLog.Level.INFO, "HfrCall", "next event")
        compose.onNodeWithText("Copier").performClick()
        val nextExport = clipboardText()
        assertTrue(nextExport.contains("next event"))
        assertFalse(nextExport.contains("Cookie.kt:19"))
    }

    @Test
    fun `clear is confirmed only after the deletion and a failed deletion offers a retry`() {
        val log = DiagnosticsLog()
        val outcomes = mutableListOf<(Boolean) -> Unit>()
        log.registerCrashReportClear { outcomes += it }
        log.recordCrash(0, "thread=main")
        show(log)
        val pending = "Suppression du dernier plantage en cours…"
        val confirmed = "Aucun rapport de plantage n’est conservé"

        compose.onNodeWithText("Vider").performClick()

        // Memory is cleared at once, but the deletion is still pending: nothing is confirmed yet.
        compose.onNodeWithText(DiagnosticsLog.CRASH_TAG).assertDoesNotExist()
        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithText(confirmed, substring = true).assertDoesNotExist()

        compose.runOnIdle { outcomes.removeAt(0)(false) }
        compose.onNodeWithText("n’a pas pu être supprimé", substring = true).assertIsDisplayed()
        compose.onNodeWithText(confirmed, substring = true).assertDoesNotExist()

        compose.onNodeWithText("Réessayer").performClick()
        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertDoesNotExist()

        compose.runOnIdle { outcomes.single()(true) }
        compose.onNodeWithText(confirmed, substring = true).assertIsDisplayed()
        compose.onNodeWithText("n’a pas pu être supprimé", substring = true).assertDoesNotExist()
    }

    @Test
    fun `test connection stays disabled until REST recovers even with no local reservation`() {
        val blocked = MutableStateFlow(true)
        val pauseMessage = "Test indisponible pendant la pause des requêtes HFR."
        show(DiagnosticsLog(), blocked, NetworkProbeState(status = NetworkProbeStatus.BLOCKED))

        compose.onNodeWithText("Tester la connexion").assertIsNotEnabled()
        compose.onNodeWithText(pauseMessage).assertIsDisplayed()

        compose.runOnIdle { blocked.value = false }

        compose.onNodeWithText("Tester la connexion").assertIsEnabled()
        compose.onNodeWithText(pauseMessage).assertDoesNotExist()
    }

    private fun show(
        log: DiagnosticsLog,
        blocked: MutableStateFlow<Boolean> = MutableStateFlow(false),
        probeState: NetworkProbeState = NetworkProbeState(),
    ) {
        val probe = mockk<NetworkProbeService> {
            every { state } returns MutableStateFlow(probeState)
            every { probeBlocked } returns blocked
            coEvery { remainingSeconds() } returns 0L
        }
        val preferences = mockk<UserPreferencesRepository> {
            every { observeFlagsRefreshPolicy() } returns flowOf(FlagsRefreshPolicy())
        }
        val factory = viewModelFactory { initializer { DiagnosticsViewModel(log, probe, preferences) } }
        val viewModel = ViewModelProvider(viewModels, factory)[DiagnosticsViewModel::class.java]
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    onClose = {},
                    versionName = "0.71.0",
                    versionCode = 75,
                    channel = "beta",
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun clipboardText(): String {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        return checkNotNull(clipboard.primaryClip).getItemAt(0).text.toString()
    }
}
