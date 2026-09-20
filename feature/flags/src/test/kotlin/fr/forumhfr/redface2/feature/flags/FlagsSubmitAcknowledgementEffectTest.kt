package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1301 — the one-shot handshake behind the flags list's submit acknowledgement: taken over exactly
 * once, and never replayed when the list is re-mounted later in the session (coming back from a
 * topic). What the acknowledgement itself looks like is covered by [FlagsSubmitAcknowledgementTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagsSubmitAcknowledgementEffectTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an armed counter is taken over once and a re-mount never replays it`() {
        val host = SnackbarHostState()
        val request = mutableIntStateOf(0)
        val mount = mutableIntStateOf(0)
        var consumed = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                // Real UI on screen on purpose: an empty composition schedules no frame under
                // Robolectric, so the effects below would never reach the main looper.
                Box(modifier = Modifier.fillMaxSize()) {
                    SnackbarHost(host)
                    // `key` stands in for leaving the flags list and coming back to it.
                    key(mount.intValue) {
                        FlagsSubmitAcknowledgementEffect(
                            request = request.intValue,
                            snackbarHostState = host,
                            message = MESSAGE,
                            onConsumed = {
                                consumed += 1
                                request.intValue = 0
                            },
                        )
                    }
                }
            }
        }

        // The state lives outside the composition, so the global-snapshot write has to be
        // published explicitly for the recomposer to see it in a test.
        compose.runOnIdle {
            request.intValue = 1
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { consumed == 1 }
        compose.onNodeWithText(MESSAGE).assertExists()

        compose.runOnIdle {
            mount.intValue += 1
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()

        assertEquals("the acknowledgement is owed exactly once", 1, consumed)
        assertEquals("the host counter must be free again", 0, request.intValue)
    }

    private companion object {
        const val MESSAGE = "Message publié"
        const val TIMEOUT_MS = 2_000L
    }
}
