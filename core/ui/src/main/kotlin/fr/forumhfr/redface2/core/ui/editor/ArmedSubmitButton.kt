package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** Display state of [ArmedSubmitButton]. [showProgress] swaps the label for a spinner. */
data class ArmedSubmitState(
    val armed: Boolean,
    val enabled: Boolean,
    val showProgress: Boolean = false,
)

/** Bare resolved strings — callers own their string resources. */
data class ArmedSubmitLabels(
    val submit: String,
    val confirm: String,
)

/** [onDisarm] fires when the countdown elapses without the confirming tap. */
data class ArmedSubmitActions(
    val onSubmit: () -> Unit,
    val onConfirmSubmit: () -> Unit,
    val onDisarm: () -> Unit,
)

/** How long the armed state survives without the confirming second tap (#312 v2). */
const val CONFIRM_DISARM_DELAY_MS = 4_000L

/**
 * M3 state layers use the content colour at low alpha to mark a state on top of a
 * container — we reuse that exact idiom for the elapsed part of the countdown, so the
 * label keeps full contrast on both the « live » and the « elapsed » halves.
 */
private const val ELAPSED_SCRIM_ALPHA = 0.16f

/**
 * Submit button with the « confirmation avant publication » (#312 v2) armed behaviour,
 * shared by the post/topic editor bar and the MP reply bar.
 *
 * First tap arms the button (label flips to [ArmedSubmitLabels.confirm], tertiary
 * colors) ; the second tap performs the real submit. While armed, the background
 * visibly drains left-to-right over [CONFIRM_DISARM_DELAY_MS] — a state-layer scrim
 * sweeps the elapsed portion — then [ArmedSubmitActions.onDisarm] restores the normal
 * state. Material 3 has no built-in timed button, so the countdown is drawn here.
 */
@Composable
fun ArmedSubmitButton(
    state: ArmedSubmitState,
    labels: ArmedSubmitLabels,
    actions: ArmedSubmitActions,
) {
    // remaining fraction of the countdown : 1f (just armed) → 0f (expired). The single
    // Animatable both paces the scrim and times the disarm, so they can never drift.
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(state.armed) {
        if (state.armed) {
            remaining.snapTo(1f)
            remaining.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = CONFIRM_DISARM_DELAY_MS.toInt(),
                    easing = LinearEasing,
                ),
            )
            actions.onDisarm()
        }
    }
    val scrim = MaterialTheme.colorScheme.onTertiary.copy(alpha = ELAPSED_SCRIM_ALPHA)
    val shape = ButtonDefaults.shape
    Button(
        enabled = state.enabled,
        onClick = if (state.armed) actions.onConfirmSubmit else actions.onSubmit,
        colors = if (state.armed) {
            // Distinct (but not destructive) palette : the second tap is a deliberate
            // confirmation, not a warning.
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .clip(shape)
            .drawWithContent {
                drawContent()
                if (state.armed) {
                    val liveWidth = size.width * remaining.value
                    drawRect(
                        color = scrim,
                        topLeft = Offset(liveWidth, 0f),
                        size = Size(size.width - liveWidth, size.height),
                    )
                }
            },
    ) {
        if (state.showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = if (state.armed) labels.confirm else labels.submit,
                maxLines = 1,
            )
        }
    }
}
