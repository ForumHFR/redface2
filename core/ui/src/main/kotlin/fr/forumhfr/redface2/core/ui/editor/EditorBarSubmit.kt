package fr.forumhfr.redface2.core.ui.editor

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Presentation adapter: no arm creation/expiry, validation, POST or legacy timer in the bar.
 * The circle is 48 dp, or 40 dp centred in a 48 dp target in compact height, in either submit state.
 */
@Composable
internal fun EditorBarSubmit(
    state: ArmedSubmitState,
    actions: ArmedSubmitActions,
    compactHeight: Boolean,
    consumeInformation: () -> Boolean,
) {
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        CONFIRM_DISARM_DELAY_MS, containsIcons = true, containsText = true, containsControls = true,
    ) ?: CONFIRM_DISARM_DELAY_MS
    val submit = stringResource(R.string.editor_bar_send)
    val confirm = stringResource(R.string.editor_bar_confirm)
    val tooltip = stringResource(if (state.armed) R.string.editor_bar_confirm_tooltip else R.string.editor_bar_send)
    val description = stringResource(if (state.armed) R.string.editor_bar_armed else R.string.editor_bar_unarmed)
    val click = {
        if (!consumeInformation()) {
            if (state.armed) actions.onConfirmSubmit()
            else actions.onSubmitWithTimeout?.invoke(timeout) ?: actions.onSubmit()
        }
    }
    val fraction = rememberEditorBarArmFraction(state)
    val metrics = EditorChromeMetrics(compactHeight)
    val scrim = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.16f)
    // The tag sits outside the tooltip anchor, which merges its descendants (a tag inside would vanish).
    Box(Modifier.testTag("editor-bar-submit")) {
        EditorIconTooltip(tooltip) {
            Button(onClick = click, enabled = state.enabled,
                contentPadding = PaddingValues(0.dp),
                shape = CircleShape,
                colors = if (state.armed) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier.size(EditorChromeMetrics.minimumTargetSize)
                    .editorBarActivation(state.enabled, click)
                    .padding((EditorChromeMetrics.minimumTargetSize - metrics.controlVisibleHeight) / 2)
                    // Tag M3's drawing Surface, inside the visual inset; native touch expansion stays 48 dp.
                    .testTag("editor-bar-submit-shape").semantics {
                        contentDescription = if (state.armed) confirm else submit
                        stateDescription = description; liveRegion = LiveRegionMode.Polite
                    },
            ) {
                // Draw inside the visible disc, after Surface's minimum interactive size.
                Box(
                    modifier = Modifier.size(metrics.controlVisibleHeight).clip(CircleShape).drawWithContent {
                        drawContent()
                        if (state.armed) drawRect(scrim, Offset(size.width * (1f - fraction), 0f),
                            Size(size.width * fraction, size.height))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    RedfaceVectorIcon(
                        if (state.armed) R.drawable.ic_editor_done else R.drawable.ic_editor_send,
                        Modifier.testTag(
                            if (state.armed) "editor-bar-submit-confirm-icon" else "editor-bar-submit-send-icon",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberEditorBarArmFraction(state: ArmedSubmitState): Float {
    var fraction by remember(state.armToken, state.armed) { mutableFloatStateOf(0f) }
    val token = state.armToken
    LaunchedEffect(token, state.armed) {
        if (token != null && state.armed) while (isActive) {
            fraction = ((SystemClock.elapsedRealtimeNanos() - token.armedAtNanos) /
                (token.timeoutMillis.coerceAtLeast(1) * 1_000_000f)).coerceIn(0f, 1f)
            if (fraction == 1f) break
            delay(16L)
        }
    }
    return fraction
}
