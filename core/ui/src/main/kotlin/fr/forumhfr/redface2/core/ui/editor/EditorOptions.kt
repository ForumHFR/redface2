@file:Suppress("MatchingDeclarationName") // Surface labels accompany the shared options component.

package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.R

/** Notification wording belongs to the surface (topic or private conversation). */
data class EditorOptionsLabels(
    val title: String,
    val signature: String,
    val smileyDisabled: String,
    val emailNotification: String,
)

/**
 * Shared HFR toggles. MP editors omit [tone]; the post supplies its existing expanded tone picker.
 * [settingsShortcut] names the current catalogue choice in the settings row; null keeps the generic description.
 */
@Composable
@Suppress("LongParameterList") // HFR options, callbacks and settings context are independent inputs.
fun EditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
    labels: EditorOptionsLabels,
    onOpenSettings: (() -> Unit)? = null,
    settingsEnabled: Boolean = enabled,
    settingsShortcut: EditorShortcut? = null,
    tone: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = labels.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionToggle(
            label = labels.signature,
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggle(
            label = labels.smileyDisabled,
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggle(
            label = labels.emailNotification,
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
        tone?.invoke()
        if (onOpenSettings != null) {
            HorizontalDivider()
            EditorSettingsOption(enabled && settingsEnabled, settingsShortcut, onOpenSettings)
        }
    }
}

@Composable
private fun OptionToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun EditorSettingsOption(enabled: Boolean, shortcut: EditorShortcut?, onClick: () -> Unit) {
    val description = editorShortcutCatalog.firstOrNull { it.choice == shortcut }
        ?.let { stringResource(R.string.editor_settings_description_shortcut, stringResource(it.labelRes)) }
        ?: stringResource(R.string.editor_settings_description)
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_ms_settings), contentDescription = null,
            modifier = Modifier.size(24.dp), tint = color,
        )
        Column {
            Text(
                stringResource(R.string.editor_settings_title), style = MaterialTheme.typography.bodyLarge,
                color = color,
            )
            Text(
                description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
