package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.HFR_MESSAGE_ICON_BASE_URL
import kotlin.math.roundToInt

/** #1417 — named, single-choice tones inside the post/topic Options sheet. */
@Composable
internal fun MessageIconPicker(
    selectedIcon: Int,
    enabled: Boolean,
    onIconSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(MSG_ICON_HEADER_TAG).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_msg_icon_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = messageIconName(selectedIcon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        MessageIconGrid(selectedIcon, enabled, onIconSelected)
    }
}

/**
 * Two full rows of eight tones at every width (16 tones, so no filler cell). A cell is one eighth of the
 * available width and [MSG_ICON_TARGET_HEIGHT] high: the vertical touch target keeps 48 dp, the horizontal
 * one does not on narrow screens (41 dp in the 328 dp useful width of a 360 dp phone), the accepted price
 * of two rows.
 */
@Composable
private fun MessageIconGrid(selectedIcon: Int, enabled: Boolean, onIconSelected: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val density = LocalDensity.current
        val spriteSize = with(density) {
            val cellPx = minOf(constraints.maxWidth / MSG_ICON_COLUMNS, MSG_ICON_TARGET_HEIGHT.roundToPx())
            val roomPx = cellPx - 2 * MSG_ICON_SELECTION_BORDER.roundToPx()
            messageIconSpritePx(MSG_ICON_SPRITE_TARGET.toPx(), roomPx).toDp()
        }
        Column {
            EDITOR_MSG_ICONS.chunked(MSG_ICON_COLUMNS).forEach { icons ->
                Row(Modifier.fillMaxWidth()) {
                    icons.forEach { icon ->
                        MessageIconOption(
                            icon = icon,
                            selected = icon == selectedIcon,
                            enabled = enabled,
                            spriteSize = spriteSize,
                            onIconSelected = onIconSelected,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sprite side in px: the integer multiple of the 15 px HFR GIF closest to [targetPx], so that
 * [FilterQuality.None] draws every source pixel as the same square (×4 at xxhdpi and 440 dpi, ×3 at xhdpi,
 * ×2 at hdpi for a 22 dp target). The multiple is lowered to fit [roomPx], never below ×1.
 * The few 16 px sprites are drawn in the same box, so their factor is not exactly an integer.
 */
internal fun messageIconSpritePx(targetPx: Float, roomPx: Int): Int {
    val scale = (targetPx / MSG_ICON_SOURCE_PX).roundToInt().coerceAtMost(roomPx / MSG_ICON_SOURCE_PX)
    return MSG_ICON_SOURCE_PX * scale.coerceAtLeast(1)
}

/** One equal-width cell of a grid row (hence the [RowScope] receiver, which carries its weight). */
@Composable
private fun RowScope.MessageIconOption(
    icon: Int,
    selected: Boolean,
    enabled: Boolean,
    spriteSize: Dp,
    onIconSelected: (Int) -> Unit,
) {
    val description = messageIconName(icon)
    val shape = MaterialTheme.shapes.small
    val selection = if (selected) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, shape)
            .border(MSG_ICON_SELECTION_BORDER, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier.weight(1f).height(MSG_ICON_TARGET_HEIGHT).clip(shape).then(selection)
            .testTag("$MSG_ICON_OPTION_TAG_PREFIX$icon")
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                onClick = { onIconSelected(icon) })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val number = rememberMessageIconNumber(icon)
        AsyncImage(
            model = "$HFR_MESSAGE_ICON_BASE_URL$icon.gif",
            contentDescription = null,
            placeholder = number,
            error = number,
            filterQuality = FilterQuality.None,
            // The numbered fallback keeps the theme's font size: let it overflow the sprite box at large scales.
            clipToBounds = false,
            modifier = Modifier.size(spriteSize).testTag("$MSG_ICON_SPRITE_TAG_PREFIX$icon"),
        )
    }
}

/** A text painter keeps the same numbered fallback for loading and error, without extra TalkBack nodes. */
@Composable
private fun rememberMessageIconNumber(icon: Int): Painter {
    val text = rememberTextMeasurer().measure(
        text = icon.toString(),
        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    )
    return remember(text) {
        object : Painter() {
            // Keep the theme's font size; fitting a text-sized intrinsic image would enlarge the number.
            override val intrinsicSize: Size = Size.Unspecified

            override fun DrawScope.onDraw() {
                val left = ((size.width - text.size.width) / 2).roundToInt().toFloat()
                val top = ((size.height - text.size.height) / 2).roundToInt().toFloat()
                drawText(text, topLeft = Offset(left, top))
            }
        }
    }
}

@Composable
private fun messageIconName(icon: Int): String =
    stringResource(MSG_ICON_NAMES.getOrElse(icon - 1) { R.string.editor_msg_icon_none })

internal const val MSG_ICON_HEADER_TAG = "message_icon_header"
internal const val MSG_ICON_OPTION_TAG_PREFIX = "message_icon_option_"
internal const val MSG_ICON_SPRITE_TAG_PREFIX = "message_icon_sprite_"
internal const val DEFAULT_MSG_ICON = 1
internal val EDITOR_MSG_ICONS = 1..16
private const val MSG_ICON_COLUMNS = 8
private const val MSG_ICON_SOURCE_PX = 15
private val MSG_ICON_SPRITE_TARGET = 22.dp
private val MSG_ICON_SELECTION_BORDER = 2.dp
private val MSG_ICON_TARGET_HEIGHT = 48.dp

// HFR MsgIcon order: page, arrow, bulb, warning, question, then the eleven face/hand sprites.
private val MSG_ICON_NAMES = listOf(
    R.string.editor_msg_icon_none,
    R.string.editor_msg_icon_arrow,
    R.string.editor_msg_icon_idea,
    R.string.editor_msg_icon_warning,
    R.string.editor_msg_icon_question,
    R.string.editor_msg_icon_cool,
    R.string.editor_msg_icon_smile,
    R.string.editor_msg_icon_angry,
    R.string.editor_msg_icon_sad,
    R.string.editor_msg_icon_laugh,
    R.string.editor_msg_icon_shocked,
    R.string.editor_msg_icon_wink,
    R.string.editor_msg_icon_thumbs_down,
    R.string.editor_msg_icon_thumbs_up,
    R.string.editor_msg_icon_surprised,
    R.string.editor_msg_icon_bored,
)

/** Browser forms expose `MsgIcon=1..16`; absent or malformed values degrade to neutral. */
internal fun String?.toEditorMsgIcon(): Int =
    this?.toIntOrNull()?.takeIf { it in EDITOR_MSG_ICONS } ?: DEFAULT_MSG_ICON
