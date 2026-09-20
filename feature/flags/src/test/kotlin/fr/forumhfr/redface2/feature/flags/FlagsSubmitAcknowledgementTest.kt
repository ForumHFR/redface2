package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1301 — the bounded acknowledgement the flags list owes a message sent from the editor it opened
 * (« Poster un message », #15). Its one-shot handshake with the host counter is covered by
 * [FlagsSubmitAcknowledgementEffectTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlagsSubmitAcknowledgementTest {

    @Test
    fun `the acknowledgement is bounded and carries the published message`() = runTest {
        val host = SnackbarHostState()

        val shown = launch { host.showSubmitAcknowledgement(MESSAGE) }
        runCurrent()

        assertEquals(MESSAGE, host.currentSnackbarData?.visuals?.message)
        // Nothing on this screen refreshes after the submit, so an Indefinite acknowledgement would
        // have no terminal condition.
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        assertNull("the acknowledgement offers no action", host.currentSnackbarData?.visuals?.actionLabel)

        shown.cancel()
    }

    @Test
    fun `the acknowledgement preempts the feedback already on screen`() = runTest {
        val host = SnackbarHostState()
        // The #99 flag-removal feedback shares this host: Material serialises showSnackbar behind
        // one mutex, so an acknowledgement that only queued would reach the reader long after the
        // action it answers.
        val removal = launch { host.showSnackbar(REMOVAL_MESSAGE) }
        runCurrent()
        assertEquals(REMOVAL_MESSAGE, host.currentSnackbarData?.visuals?.message)

        val shown = launch { host.showSubmitAcknowledgement(MESSAGE) }
        runCurrent()

        assertTrue("the older feedback must be released, not left holding the host", removal.isCompleted)
        assertEquals(MESSAGE, host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)

        shown.cancel()
    }

    private companion object {
        const val MESSAGE = "Message publié"
        const val REMOVAL_MESSAGE = "Drapeau retiré : Topic Redface 2"
    }
}
