package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice

/**
 * Settings card « Écran de démarrage » (#458): segmented choice of the cold-start tab, plus —
 * for the Forum tab — an optional category picker (« Accueil du forum » = no pre-stacked
 * category). The selection applies on the NEXT launch (the intro line says so): the running
 * session is never teleported.
 */
@Composable
internal fun StartScreenPreferencesCard(
    state: StartScreenSettingsState,
    onIntent: (StartScreenSettingsIntent) -> Unit,
) {
    val options = listOf(
        StartScreenChoice.FLAGS to stringResource(R.string.settings_start_screen_flags),
        StartScreenChoice.FORUM to stringResource(R.string.settings_start_screen_forum),
        StartScreenChoice.MESSAGES to stringResource(R.string.settings_start_screen_messages),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_start_screen_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_start_screen_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (choice, label) ->
                    SegmentedButton(
                        selected = state.preference.screen == choice,
                        enabled = state.canChange,
                        onClick = { onIntent(StartScreenSettingsIntent.ScreenChanged(choice)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
            if (state.preference.screen == StartScreenChoice.FORUM) {
                StartForumCategoryPicker(state = state, onIntent = onIntent)
            }
            if (state.persistError) {
                PreferencePersistError(R.string.settings_start_screen_persist_failed)
            }
        }
    }
}

/**
 * Category picker of the Forum start screen — same `ExposedDropdownMenuBox` + read-only field
 * pattern as the editor's subcategory dropdown. The first entry (« Accueil du forum ») maps to
 * `forumCatId = null`; a failed category fetch only degrades the helper text, the root choice
 * keeps working offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartForumCategoryPicker(
    state: StartScreenSettingsState,
    onIntent: (StartScreenSettingsIntent) -> Unit,
) {
    val rootLabel = stringResource(R.string.settings_start_screen_category_root)
    val selectedLabel = state.categories
        .firstOrNull { it.id == state.preference.forumCatId }
        ?.name
        ?: rootLabel
    var expanded by remember { mutableStateOf(false) }
    val menuEnabled = state.canChange

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = menuEnabled && it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = menuEnabled,
            label = { Text(stringResource(R.string.settings_start_screen_category_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = menuEnabled),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(rootLabel) },
                onClick = {
                    expanded = false
                    onIntent(StartScreenSettingsIntent.ForumCategoryChanged(null))
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        expanded = false
                        onIntent(StartScreenSettingsIntent.ForumCategoryChanged(category.id))
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
    if (state.categoriesError) {
        Text(
            text = stringResource(R.string.settings_start_screen_categories_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
