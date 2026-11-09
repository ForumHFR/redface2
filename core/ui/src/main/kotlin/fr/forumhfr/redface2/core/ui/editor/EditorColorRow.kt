package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
) {
    EditorBarIcon(R.drawable.ic_arrow_back, stringResource(R.string.editor_return), onBack, backModifier)
    val palette = listOf(
        "#FF0000" to R.string.bbcode_color_red, "#0000FF" to R.string.bbcode_color_blue,
        "#008000" to R.string.bbcode_color_green, "#FF6600" to R.string.bbcode_color_orange,
        "#808080" to R.string.bbcode_color_grey,
    )
    palette.forEach { (hex, resource) ->
        val label = stringResource(resource)
        val click = { onAction(BbcodeAction.Color(hex)) }
        val outline = MaterialTheme.colorScheme.onSurface
        EditorIconTooltip(label) {
            IconButton(onClick = click, enabled = enabled,
                modifier = Modifier.size(EditorChromeMetrics.minimumTargetSize).editorBarActivation(enabled, click)
                    .semantics { contentDescription = label }) {
                Box(Modifier.size(24.dp).alpha(if (enabled) 1f else DISABLED_SWATCH_ALPHA).clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(hex)))
                    .drawBehind { drawCircle(outline, style = Stroke(1.dp.toPx())) })
            }
        }
    }
}

private const val DISABLED_SWATCH_ALPHA = 0.38f
