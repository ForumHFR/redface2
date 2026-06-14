package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup

/**
 * Settings block « Écran de démarrage » (#458): segmented choice of the cold-start tab, plus —
 * for the Forum tab — an optional category picker (« Accueil du forum » = no pre-stacked
 * category). The selection applies on the NEXT launch (the intro line says so): the running
 * session is never teleported.
 *
 * #494 — the surrounding `Card` was dropped: the block now renders inline inside the settings
 * `LazyColumn` (the « Démarrage » section stays at the root, it is not a sub-page). The 3-way
 * selector uses the shared [RedfaceSettingsChoiceGroup]; the category picker keeps its bespoke
 * `ExposedDropdownMenuBox` (not covered by the list primitives).
 */
@Composable
internal fun StartScreenPreferencesCard(
    state: StartScreenSettingsState,
    onIntent: (StartScreenSettingsIntent) -> Unit,
) {
    val options = listOf(
        RedfaceSettingsChoice(StartScreenChoice.FLAGS, stringResource(R.string.settings_start_screen_flags)),
        RedfaceSettingsChoice(StartScreenChoice.FORUM, stringResource(R.string.settings_start_screen_forum)),
        RedfaceSettingsChoice(StartScreenChoice.MESSAGES, stringResource(R.string.settings_start_screen_messages)),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_start_screen_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RedfaceSettingsChoiceGroup(
            options = options,
            selected = state.preference.screen,
            onSelected = { onIntent(StartScreenSettingsIntent.ScreenChanged(it)) },
            enabled = state.canChange,
        )
        if (state.preference.screen == StartScreenChoice.FORUM) {
            StartForumCategoryPicker(state = state, onIntent = onIntent)
        }
        if (state.persistError) {
            PreferencePersistError(R.string.settings_start_screen_persist_failed)
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
    val selectedCatId = state.preference.forumCatId
    // A persisted category that the (not yet loaded / failed / stale) category list cannot
    // resolve must NOT read as « Accueil du forum » — the next launch would still open it
    // (review Codex PR #464). Show an explicit id-based fallback instead.
    val selectedLabel = when (selectedCatId) {
        null -> rootLabel
        else -> state.categories.firstOrNull { it.id == selectedCatId }?.name
            ?: stringResource(R.string.settings_start_screen_category_unresolved, selectedCatId)
    }
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
