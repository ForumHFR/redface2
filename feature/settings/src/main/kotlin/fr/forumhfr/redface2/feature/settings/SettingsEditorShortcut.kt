package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorColorSwatch
import fr.forumhfr.redface2.core.ui.editor.EditorShortcutEntry
import fr.forumhfr.redface2.core.ui.editor.editorShortcutCatalog
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSection

/** Each choice is a real searchable catalogue row; restrictions are displayed, never inferred from icons. */
@Composable
internal fun editorShortcutRows(state: SettingsState, onIntent: (SettingsIntent) -> Unit): List<SettingsCatalogueRow> {
    val group = stringResource(R.string.settings_editor_shortcut_title)
    return editorShortcutCatalog.map { entry ->
        val label = stringResource(entry.labelRes)
        val description = when (entry.choice) {
            EditorShortcut.NONE -> R.string.settings_editor_shortcut_none
            EditorShortcut.IMAGE_URL -> R.string.settings_editor_shortcut_post_topic
            EditorShortcut.IMAGE_SELECTION -> R.string.settings_editor_shortcut_messages
            else -> null
        }?.let { stringResource(it) }
        val selected = state.editorShortcut == entry.choice
        SettingsCatalogueRow(
            searchable = SettingsSearchableItem(id = "editor_shortcut_${entry.choice.id}", title = label,
                description = description, keywords = listOf(group, "raccourci", "barre")),
            render = {
                Column {
                    RedfaceSettingsListItem(label, description = description, enabled = state.canChangeEditorShortcut,
                        modifier = Modifier.selectable(selected, enabled = state.canChangeEditorShortcut,
                            role = Role.RadioButton,
                            onClick = { onIntent(SettingsIntent.SetEditorShortcut(entry.choice)) }),
                        leadingContent = { ShortcutGlyph(entry) },
                        trailingContent = {
                            RadioButton(selected, onClick = null, enabled = state.canChangeEditorShortcut)
                        })
                    if (selected && state.editorShortcutError) {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            PreferencePersistError(R.string.settings_editor_shortcut_persist_failed)
                        }
                    }
                }
            },
        )
    }
}

internal val SettingsSearchableItem.isEditorShortcut: Boolean
    get() = id.startsWith("editor_shortcut_")

/** Wrap the retained choices together: the group heading survives filtering out NONE. */
@Composable
internal fun EditorShortcutGroup(content: @Composable () -> Unit) {
    Column(Modifier.selectableGroup()) {
        RedfaceSettingsSection(stringResource(R.string.settings_editor_shortcut_title),
            modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun ShortcutGlyph(entry: EditorShortcutEntry) {
    val color = entry.bbcode as? BbcodeAction.Color
    val icon = entry.iconRes
    when {
        color != null -> EditorColorSwatch(color.colorHex)
        icon != null -> Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        else -> Box(Modifier.size(24.dp))
    }
}
