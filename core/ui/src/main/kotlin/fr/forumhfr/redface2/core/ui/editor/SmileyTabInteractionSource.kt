package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * The consultation Tab keeps real focus without a focus overlay in touch mode. Only Focus is dropped:
 * an Unfocus always passes, so a keyboard focus followed by a touch never leaves the overlay stuck.
 */
internal class SmileyTabInteractionSource(
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
    private val isTouchMode: () -> Boolean,
) : MutableInteractionSource {
    override val interactions = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        if (interaction is FocusInteraction.Focus && isTouchMode()) return
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        if (interaction is FocusInteraction.Focus && isTouchMode()) return true
        return delegate.tryEmit(interaction)
    }
}
