package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.HFR_MESSAGE_ICON_BASE_URL

/** #340 — collapsed-by-default picker for the 16 tones HFR exposes on write forms. */
@Composable
internal fun MessageIconPicker(
    selectedIcon: Int,
    enabled: Boolean,
    onIconSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toneDescription = stringResource(R.string.editor_msg_icon_tone_description, selectedIcon)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MSG_ICON_PICKER_TOGGLE_TAG)
                .clickable(enabled = enabled, role = Role.Button) { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_msg_icon_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selectedIcon == DEFAULT_MSG_ICON) {
                Text(
                    text = stringResource(R.string.editor_msg_icon_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    model = "$HFR_MESSAGE_ICON_BASE_URL$selectedIcon.gif",
                    contentDescription = toneDescription,
                    modifier = Modifier.size(CURRENT_MSG_ICON_SIZE),
                )
            }
            // Purely decorative affordance : the expand/collapse state is already carried by the
            // clickable Row. `material-icons-core` is out of reach (detekt ForbiddenImport bans
            // `androidx.compose.material.*`), so the glyph is muted for TalkBack instead.
            Text(
                text = if (expanded) "⌃" else "⌄",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (expanded) {
            MessageIconGrid(
                selectedIcon = selectedIcon,
                enabled = enabled,
                onIconSelected = onIconSelected,
            )
        }
    }
}

@Composable
private fun MessageIconGrid(
    selectedIcon: Int,
    enabled: Boolean,
    onIconSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EDITOR_MSG_ICONS.chunked(MSG_ICON_COLUMNS).forEach { icons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                icons.forEach { icon ->
                    val selected = icon == selectedIcon
                    val description = if (icon == DEFAULT_MSG_ICON) {
                        stringResource(R.string.editor_msg_icon_none_description)
                    } else {
                        stringResource(R.string.editor_msg_icon_tone_description, icon)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MSG_ICON_TARGET_MIN_HEIGHT)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("$MSG_ICON_OPTION_TAG_PREFIX$icon")
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onIconSelected(icon) },
                            )
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = "$HFR_MESSAGE_ICON_BASE_URL$icon.gif",
                            contentDescription = null,
                            modifier = Modifier.size(GRID_MSG_ICON_SIZE),
                        )
                    }
                }
            }
        }
    }
}

internal const val MSG_ICON_PICKER_TOGGLE_TAG = "message_icon_picker_toggle"
internal const val MSG_ICON_OPTION_TAG_PREFIX = "message_icon_option_"
internal const val DEFAULT_MSG_ICON = 1
private const val MSG_ICON_COLUMNS = 8
internal val EDITOR_MSG_ICONS = 1..16
private val CURRENT_MSG_ICON_SIZE = 20.dp
private val GRID_MSG_ICON_SIZE = 24.dp
private val MSG_ICON_TARGET_MIN_HEIGHT = 48.dp

/** Browser forms expose `MsgIcon=1..16`; absent or malformed values degrade to neutral. */
internal fun String?.toEditorMsgIcon(): Int =
    this?.toIntOrNull()?.takeIf { it in EDITOR_MSG_ICONS } ?: DEFAULT_MSG_ICON
