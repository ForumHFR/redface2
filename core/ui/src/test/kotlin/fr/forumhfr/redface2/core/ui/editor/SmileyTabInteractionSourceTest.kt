package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observe the indication's interaction stream directly, independently of JVM ripple rendering. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmileyTabInteractionSourceTest {
    @Test
    fun `emit filters touch focus but keeps keyboard focus and press interactions`() = checkInteractions(false)

    @Test
    fun `tryEmit filters touch focus but keeps keyboard focus and press interactions`() = checkInteractions(true)

    private fun checkInteractions(useTryEmit: Boolean) = runTest {
        var touch = true
        val source = SmileyTabInteractionSource { touch }
        val seen = mutableListOf<Interaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.interactions.collect { seen += it } }
        suspend fun send(interaction: Interaction) {
            if (useTryEmit) assertTrue(source.tryEmit(interaction)) else source.emit(interaction)
        }

        // Touch focus is dropped; an Unfocus without its Focus is a no-op for the indication and passes.
        val touchFocus = FocusInteraction.Focus()
        send(touchFocus)
        val touchUnfocus = FocusInteraction.Unfocus(touchFocus)
        send(touchUnfocus)

        val press = PressInteraction.Press(Offset.Zero)
        val release = PressInteraction.Release(press)
        val cancel = PressInteraction.Cancel(press)
        listOf(press, release, cancel).forEach { send(it) }

        // The callback reads the current mode at emission time, including D-pad/keyboard entry.
        touch = false
        val keyboardFocus = FocusInteraction.Focus()
        val keyboardUnfocus = FocusInteraction.Unfocus(keyboardFocus)
        send(keyboardFocus)
        send(keyboardUnfocus)

        // Keyboard focus, then a touch switches the mode: the Unfocus must still clear the overlay.
        val laterFocus = FocusInteraction.Focus()
        send(laterFocus)
        touch = true
        val laterUnfocus = FocusInteraction.Unfocus(laterFocus)
        send(laterUnfocus)

        runCurrent()
        assertEquals(
            listOf(touchUnfocus, press, release, cancel, keyboardFocus, keyboardUnfocus, laterFocus, laterUnfocus),
            seen,
        )
    }
}
