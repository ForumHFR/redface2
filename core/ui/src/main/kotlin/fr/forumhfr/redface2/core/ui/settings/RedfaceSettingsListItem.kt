package fr.forumhfr.redface2.core.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * #494 — reusable Material 3 building blocks for the redesigned settings catalogue.
 *
 * These primitives keep `:feature:settings` (and any future settings host) free of bespoke row
 * layouts: the screen composes [RedfaceSettingsSection] headers, [RedfaceSettingsListItem] rows
 * (navigation, toggle, or plain) and [RedfaceSettingsChoiceGroup] segmented selectors, while the
 * dividers between rows stay the caller's responsibility (M3 `HorizontalDivider`).
 *
 * Material 3 stable only — no `androidx.compose.material.*` / material-icons (forbidden project-wide).
 */

/**
 * Section header for the settings catalogue (e.g. « Affichage », « Drapeaux »). Renders the [title]
 * in the M3 `titleSmall` type and the `primary` colour, with the standard list inset padding so it
 * lines up with the [RedfaceSettingsListItem] rows below it.
 */
@Composable
fun RedfaceSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * A single settings row, wrapping the Material 3 [ListItem].
 *
 * - [title] is the headline; [description] (optional) is the supporting line.
 * - [leadingContent] / [trailingContent] are optional slots (e.g. a `Switch` trailing a toggle row,
 *   or a chevron trailing a navigation row).
 * - When [onClick] is non-null the whole row is clickable (gated by [enabled]); a `null` [onClick]
 *   leaves the row inert so a toggle row can keep its `Switch` as the only interactive element.
 * - When [enabled] is `false` the text is tinted `onSurfaceVariant` — the M3 idiom for an inactive
 *   row, preferred over a blanket `Modifier.alpha`. Disabled rows are not clickable and still appear
 *   in search (the caller decides searchability, not this primitive).
 */
@Composable
@Suppress("LongParameterList") // Compose list-item API: optional defaulted slots, each a distinct surface.
fun RedfaceSettingsListItem(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val headlineColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    ListItem(
        headlineContent = {
            Text(text = title, color = headlineColor)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
        supportingContent = description?.let {
            { Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * One option of a [RedfaceSettingsChoiceGroup]: the backing [value] (an enum, typically) and its
 * localized [label].
 */
data class RedfaceSettingsChoice<T>(val value: T, val label: String)

/**
 * A text-only single-choice segmented selector, replicating the pattern used across the settings
 * cards (theme, density, font scale, upload provider). [selected] is compared by equality against
 * each [RedfaceSettingsChoice.value]; [onSelected] is called with the picked value. [enabled] gates
 * every button at once (e.g. while a DataStore write is in flight).
 */
@Composable
fun <T> RedfaceSettingsChoiceGroup(
    options: List<RedfaceSettingsChoice<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option.value,
                enabled = enabled,
                onClick = { onSelected(option.value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(option.label)
            }
        }
    }
}
