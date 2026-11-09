package fr.forumhfr.redface2.core.ui.editor

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Presentation adapter: no arm creation/expiry, validation, POST or legacy timer in the bar. */
@Composable
internal fun EditorBarSubmit(
    state: ArmedSubmitState,
    actions: ArmedSubmitActions,
    consumeInformation: () -> Boolean,
) {
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        CONFIRM_DISARM_DELAY_MS, containsIcons = false, containsText = true, containsControls = true,
    ) ?: CONFIRM_DISARM_DELAY_MS
    val submit = stringResource(R.string.editor_bar_send)
    val confirm = stringResource(R.string.editor_bar_confirm)
    val description = stringResource(if (state.armed) R.string.editor_bar_armed else R.string.editor_bar_unarmed)
    val click = {
        if (!consumeInformation()) {
            if (state.armed) actions.onConfirmSubmit()
            else actions.onSubmitWithTimeout?.invoke(timeout) ?: actions.onSubmit()
        }
    }
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
    val scrim = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.16f)
    Button(onClick = click, enabled = state.enabled,
        colors = if (state.armed) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ) else ButtonDefaults.buttonColors(),
        modifier = Modifier.height(48.dp).testTag("editor-bar-submit")
            .editorBarActivation(state.enabled, click)
            .semantics { stateDescription = description; liveRegion = LiveRegionMode.Polite }
            .clip(ButtonDefaults.shape)
            .drawWithContent {
                drawContent()
                if (state.armed) drawRect(scrim, Offset(size.width * (1f - fraction), 0f),
                    Size(size.width * fraction, size.height))
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(submit, Modifier.alpha(0f).clearAndSetSemantics {}, maxLines = 1)
            Text(confirm, Modifier.alpha(0f).clearAndSetSemantics {}, maxLines = 1)
            Text(if (state.armed) confirm else submit, maxLines = 1)
        }
    }
}
