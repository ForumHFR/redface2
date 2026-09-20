package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1301 — the bounded acknowledgement the flags list owes a message sent from the editor it opened
 * (« Poster un message », #15). No `SnackbarHost` is composed on purpose: the auto-dismiss timer
 * lives in that composable, so the snackbar stays inspectable for the length of the assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagsSubmitAcknowledgementTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an armed acknowledgement shows once, bounded, and frees the host counter`() {
        val host = SnackbarHostState()
        val request = mutableIntStateOf(0)
        var consumed = 0
        compose.setContent {
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

        compose.runOnIdle { request.intValue = 1 }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData?.visuals?.message == MESSAGE }

        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        assertEquals("the acknowledgement is taken over exactly once", 1, consumed)
        assertEquals("the host counter must be free again", 0, request.intValue)
    }

    @Test
    fun `the acknowledgement preempts the snackbar already on screen`() {
        val host = SnackbarHostState()
        val request = mutableIntStateOf(0)
        compose.setContent {
            // The #99 flag-removal feedback shares this host and would otherwise hold it: Material
            // serialises showSnackbar behind one mutex, so an acknowledgement that only queued
            // would reach the reader long after the action it answers.
            LaunchedEffect(Unit) { host.showSnackbar(REMOVAL_MESSAGE) }
            FlagsSubmitAcknowledgementEffect(
                request = request.intValue,
                snackbarHostState = host,
                message = MESSAGE,
                onConsumed = { request.intValue = 0 },
            )
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData?.visuals?.message == REMOVAL_MESSAGE }

        compose.runOnIdle { request.intValue = 1 }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData?.visuals?.message == MESSAGE }

        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
    }

    @Test
    fun `a list re-mounted after the acknowledgement never replays it`() {
        val host = SnackbarHostState()
        val request = mutableIntStateOf(0)
        val mount = mutableIntStateOf(0)
        compose.setContent {
            key(mount.intValue) {
                FlagsSubmitAcknowledgementEffect(
                    request = request.intValue,
                    snackbarHostState = host,
                    message = MESSAGE,
                    onConsumed = { request.intValue = 0 },
                )
            }
        }
        compose.runOnIdle { request.intValue = 1 }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData?.visuals?.message == MESSAGE }
        compose.runOnIdle { host.currentSnackbarData?.dismiss() }

        // Leaving the flags list and coming back to it later in the session re-runs the effect
        // against a counter the host already reset: nothing is owed any more.
        compose.runOnIdle { mount.intValue += 1 }
        compose.waitForIdle()

        assertNull("an acknowledgement already given must not reappear", host.currentSnackbarData)
    }

    private companion object {
        const val MESSAGE = "Message publié"
        const val REMOVAL_MESSAGE = "Drapeau retiré : Topic Redface 2"
        const val TIMEOUT_MS = 1_000L
    }
}
