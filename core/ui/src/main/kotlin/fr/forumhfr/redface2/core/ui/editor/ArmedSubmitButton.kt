package fr.forumhfr.redface2.core.ui.editor

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Display state of [ArmedSubmitButton]. [showProgress] swaps the label for a spinner. */
data class ArmedSubmitState(
    val armed: Boolean,
    val enabled: Boolean,
    val showProgress: Boolean = false,
    val armToken: ArmToken? = null,
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
    val onSubmitWithTimeout: ((Long) -> Unit)? = null,
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
 * ED-164/166: renders the controller's monotonic arm interval; drawing never expires a token.
 * Accessibility timeout is captured by the first submit intent. Animator duration scale is irrelevant.
 */
@Composable
fun ArmedSubmitButton(
    state: ArmedSubmitState,
    labels: ArmedSubmitLabels,
    actions: ArmedSubmitActions,
) {
    val timeoutMillis = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        CONFIRM_DISARM_DELAY_MS, containsIcons = false, containsText = true, containsControls = true,
    ) ?: CONFIRM_DISARM_DELAY_MS
    val elapsedFraction = rememberArmElapsedFraction(state, timeoutMillis, actions.onDisarm)
    val scrim = MaterialTheme.colorScheme.onTertiary.copy(alpha = ELAPSED_SCRIM_ALPHA)
    val shape = ButtonDefaults.shape
    Button(
        enabled = state.enabled,
        onClick = {
            if (state.armed) {
                actions.onConfirmSubmit()
            } else {
                val submitWithTimeout = actions.onSubmitWithTimeout
                if (submitWithTimeout == null) actions.onSubmit() else submitWithTimeout(timeoutMillis)
            }
        },
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
                    val liveWidth = size.width * (1f - elapsedFraction)
                    drawRect(
                        color = scrim,
                        topLeft = Offset(liveWidth, 0f),
                        size = Size(size.width - liveWidth, size.height),
                    )
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // ED-34: both labels reserve their natural width, keeping the same right edge on arming.
            Text(labels.submit, Modifier.alpha(0f).clearAndSetSemantics {}, maxLines = 1)
            Text(labels.confirm, Modifier.alpha(0f).clearAndSetSemantics {}, maxLines = 1)
            if (state.showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = if (state.armed) labels.confirm else labels.submit, maxLines = 1)
            }
        }
    }
}

/** Decorative sampling only; a controller token is expired exclusively by its ViewModel. */
@Composable
private fun rememberArmElapsedFraction(
    state: ArmedSubmitState,
    timeoutMillis: Long,
    onLegacyDisarm: () -> Unit,
): Float {
    val token = state.armToken
    // Legacy MP callers retain a local timer until their controller migration (3b).
    val startedAt = remember(state.armed, token) { token?.armedAtNanos ?: SystemClock.elapsedRealtimeNanos() }
    val duration = token?.timeoutMillis ?: timeoutMillis
    var elapsedFraction by remember(token, state.armed) { mutableFloatStateOf(0f) }
    LaunchedEffect(state.armed, token) {
        while (isActive && state.armed) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000f
            elapsedFraction = (elapsed / duration).coerceIn(0f, 1f)
            if (elapsed >= duration) {
                // A controller token expires in the ViewModel, never from an animation callback.
                if (token == null) onLegacyDisarm()
                break
            }
            delay(16L)
        }
    }
    return elapsedFraction
}
