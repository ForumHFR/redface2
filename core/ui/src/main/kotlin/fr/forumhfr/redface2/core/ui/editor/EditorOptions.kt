@file:Suppress("MatchingDeclarationName") // Surface labels accompany the shared options component.

package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Notification wording belongs to the surface (topic or private conversation). */
data class EditorOptionsLabels(
    val title: String,
    val signature: String,
    val smileyDisabled: String,
    val emailNotification: String,
)

/** Shared HFR toggles. MP editors omit [tone]; the post supplies its existing expanded tone picker. */
@Composable
@Suppress("LongParameterList") // HFR options + callbacks + enabled — each is user-editable.
fun EditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
    labels: EditorOptionsLabels,
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
