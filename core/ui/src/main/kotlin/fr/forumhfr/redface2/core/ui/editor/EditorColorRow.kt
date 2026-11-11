package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/** Drawn swatches are the ED-36 exception to the local vector icon family. No popup or IME call. */
@Composable
internal fun EditorColorRow(
    onAction: (BbcodeAction) -> Unit,
    onBack: () -> Unit,
    enabled: Boolean,
    backModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    EditorBarIcon(R.drawable.ic_arrow_back, stringResource(R.string.editor_return), onBack, backModifier,
        compactHeight = compactHeight)
    editorShortcutColors.forEach { entry ->
        val action = entry.bbcode as BbcodeAction.Color
        EditorColorIcon(action.colorHex, stringResource(entry.labelRes), { onAction(action) }, enabled, compactHeight)
    }
}

@Composable
internal fun EditorColorIcon(hex: String, label: String, click: () -> Unit, enabled: Boolean, compactHeight: Boolean) {
    EditorIconTooltip(label) {
        Box(Modifier.editorCompactTargetSlot(compactHeight), contentAlignment = Alignment.Center) {
            IconButton(onClick = click, enabled = enabled,
                modifier = Modifier.size(EditorChromeMetrics(compactHeight).controlVisibleHeight)
                    .editorBarActivation(enabled, click).semantics { contentDescription = label }) {
                EditorColorSwatch(hex, Modifier.alpha(if (enabled) 1f else DISABLED_SWATCH_ALPHA))
            }
        }
    }
}

/** The same drawn 24 dp swatch in the bar, the color row and the settings catalogue (ED-36). */
@Composable
fun EditorColorSwatch(hex: String, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(24.dp).clip(CircleShape)
        .background(Color(android.graphics.Color.parseColor(hex)))
        .drawBehind {
            val stroke = 1.dp.toPx()
            drawCircle(outline, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        })
}

private const val DISABLED_SWATCH_ALPHA = 0.38f
