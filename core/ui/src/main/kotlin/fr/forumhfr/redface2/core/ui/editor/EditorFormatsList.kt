package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * #1417 point 5 (#1513): the « Formats » tab of the Options sheet: one sentence of help, then
 * [EditorFormatsList]. The host applies a tapped format to the selection captured when the sheet opened
 * (ED-22), closes the sheet and gives focus and keyboard back to the body (ED-80): this tab only
 * reports gestures.
 */
@Composable
internal fun EditorFormatsPage(onFormat: (BbcodeAction) -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_formats_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 8.dp),
        )
        EditorFormatsList(onFormat, enabled)
    }
}

/**
 * The twelve ED-20 formats in the order of the format row: icon, name, the tags the action really
 * inserts (monospace) and the effect. Couleur opens no sublevel: its five ED-24 tints sit under it,
 * each a 48 dp target. [enabled] follows the insertion lock (read-only body, upload, armed send).
 */
@Composable
internal fun EditorFormatsList(onFormat: (BbcodeAction) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(EDITOR_FORMATS_LIST_TAG)) {
        editorShortcutFormatsBeforeColor.forEach { EditorFormatOption(it, enabled, onFormat) }
        EditorFormatColors(enabled, onFormat)
        editorShortcutFormatsAfterColor.forEach { EditorFormatOption(it, enabled, onFormat) }
    }
}

@Composable
private fun EditorFormatOption(entry: EditorShortcutEntry, enabled: Boolean, onFormat: (BbcodeAction) -> Unit) {
    val action = checkNotNull(entry.bbcode)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = FormatRowMinHeight)
            .testTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + entry.choice.id)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = stringResource(R.string.editor_formats_apply)) { onFormat(action) }
            .padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 8.dp)
            .alpha(if (enabled) 1f else DISABLED_SWATCH_ALPHA),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorFormatLabel(checkNotNull(entry.iconRes), stringResource(entry.labelRes),
            entry.descriptionRes?.let { stringResource(it) }, editorFormatTags(action))
    }
}

/** A described heading for the tints: TalkBack reads « Couleur, texte en couleur… » then each tint. */
@Composable
private fun EditorFormatColors(enabled: Boolean, onFormat: (BbcodeAction) -> Unit) {
    val fade = Modifier.alpha(if (enabled) 1f else DISABLED_SWATCH_ALPHA)
    Column(Modifier.fillMaxWidth().testTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + "color")) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FormatRowMinHeight)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 8.dp).then(fade),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorFormatLabel(R.drawable.ic_editor_color, stringResource(R.string.bbcode_action_color),
                stringResource(R.string.editor_format_desc_color),
                editorFormatTags(checkNotNull(editorShortcutColors.first().bbcode)))
        }
        // 16 dp gutter + 24 dp icon + 16 dp gap, minus the 12 dp inset of a 48 dp target around a 24 dp swatch.
        Row(Modifier.padding(start = 44.dp, bottom = 4.dp)) {
            editorShortcutColors.forEach { entry ->
                val color = entry.bbcode as BbcodeAction.Color
                val label = stringResource(entry.labelRes)
                Box(
                    modifier = Modifier.size(EditorChromeMetrics.minimumTargetSize).clip(CircleShape)
                        .testTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + entry.choice.id)
                        .clickable(enabled = enabled, role = Role.Button,
                            onClickLabel = stringResource(R.string.editor_formats_apply)) { onFormat(color) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    EditorColorSwatch(color.colorHex, fade)
                }
            }
        }
    }
}

/**
 * Name and tags share the first line; the effect reads under them. The raw tags are for the eye only:
 * their semantics are cleared, so TalkBack reads « Gras, Texte en gras » and never spells brackets.
 */
@Composable
private fun RowScope.EditorFormatLabel(icon: Int, label: String, effect: String?, tags: String) {
    RedfaceVectorIcon(icon, tint = MaterialTheme.colorScheme.onSurface)
    Column(Modifier.weight(1f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            Text(
                text = tags,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (effect != null) {
            Text(effect, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** « [b]…[/b] », or « [*] » alone: the tags come from the action itself, never from a translated string. */
internal fun editorFormatTags(action: BbcodeAction): String =
    if (action.closeTag.isEmpty()) action.openTag else "${action.openTag}…${action.closeTag}"

internal const val EDITOR_FORMATS_LIST_TAG = "editor-formats-list"
internal const val EDITOR_FORMATS_OPTION_TAG_PREFIX = "editor-formats-option-"
private val FormatRowMinHeight = 56.dp
