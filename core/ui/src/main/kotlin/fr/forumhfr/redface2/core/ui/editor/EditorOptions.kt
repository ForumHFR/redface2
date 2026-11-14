@file:Suppress("MatchingDeclarationName") // Surface labels accompany the shared options component.

package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** Notification wording belongs to the surface (topic or private conversation). */
data class EditorOptionsLabels(
    val title: String,
    val signature: String,
    val smileyDisabled: String,
    val emailNotification: String,
)

/**
 * Options tab of the sheet (#1417, decision of 2026-11-13), in this order: « Réglages de l'éditeur »
 * first, then the surface's [tone] picker, then the three HFR toggles. MP editors omit [tone]; the post
 * and the topic form supply their expanded tone grid. The sheet's switch names the tab, so the surface
 * title is only the accessible pane title. Rows are full-bleed and apply the editor gutter themselves.
 * [settingsShortcut] names the current catalogue choice in the settings row; null keeps the generic
 * description.
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
    Column(Modifier.fillMaxWidth().semantics { paneTitle = labels.title }) {
        if (onOpenSettings != null) {
            OptionNavigation(R.drawable.ic_ms_settings, stringResource(R.string.editor_settings_title),
                editorSettingsDescription(settingsShortcut), enabled && settingsEnabled, onOpenSettings)
            HorizontalDivider(Modifier.padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 4.dp))
        }
        if (tone != null) {
            Box(Modifier.padding(horizontal = EditorChromeMetrics.horizontalGutter)) { tone() }
        }
        OptionToggle(labels.signature, signatureEnabled, enabled, onSignatureChanged)
        OptionToggle(labels.smileyDisabled, smileyDisabled, enabled, onSmileyDisabledChanged)
        OptionToggle(labels.emailNotification, emailNotificationEnabled, enabled, onEmailNotificationChanged)
    }
}

/** The whole row is the switch target (Role.Switch); the Switch itself is not focusable on its own. */
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
            .heightIn(min = OptionRowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun editorSettingsDescription(shortcut: EditorShortcut?): String =
    editorShortcutCatalog.firstOrNull { it.choice == shortcut }
        ?.let { stringResource(R.string.editor_settings_description_shortcut, stringResource(it.labelRes)) }
        ?: stringResource(R.string.editor_settings_description)

/** Icon, title, one supporting line and a chevron: the row that leaves the sheet for the settings. */
@Composable
private fun OptionNavigation(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NavigationRowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = EditorChromeMetrics.horizontalGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RedfaceVectorIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RedfaceVectorIcon(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val OptionRowMinHeight = 48.dp
private val NavigationRowMinHeight = 64.dp
