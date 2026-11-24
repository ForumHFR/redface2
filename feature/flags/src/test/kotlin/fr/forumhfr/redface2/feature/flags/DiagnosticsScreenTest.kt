package fr.forumhfr.redface2.feature.flags

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val viewModels = ViewModelStore()

    @After
    fun clearViewModels() = viewModels.clear()

    @Test
    fun `copy includes the redacted crash and clear removes it from the screen and the next export`() {
        val log = DiagnosticsLog()
        log.recordCrash(0, "Editor failed at https://forum.hardware.fr/mp/654321?hash_check=SecretHash")
        val probe = mockk<NetworkProbeService> {
            every { state } returns MutableStateFlow(NetworkProbeState())
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

        compose.onNodeWithText("pile du dernier plantage", substring = true).assertIsDisplayed()
        compose.onNodeWithText(DiagnosticsLog.CRASH_TAG).assertIsDisplayed()
        compose.onNodeWithText("Copier").performClick()
        val exportedCrash = clipboardText()
        assertTrue(exportedCrash.contains("Editor failed at <url>"))
        assertFalse(exportedCrash.contains("SecretHash"))

        compose.onNodeWithText("Vider").performClick()
        compose.onNodeWithText(DiagnosticsLog.CRASH_TAG).assertDoesNotExist()
        compose.onNodeWithText("Copier").assertIsNotEnabled()

        log.record(DiagnosticsLog.Level.INFO, "HfrCall", "next event")
        compose.onNodeWithText("Copier").performClick()
        val nextExport = clipboardText()
        assertTrue(nextExport.contains("next event"))
        assertFalse(nextExport.contains("Editor failed"))
    }

    private fun clipboardText(): String {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        return checkNotNull(clipboard.primaryClip).getItemAt(0).text.toString()
    }
}
