package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/** One selectable tab of the Drapeaux app bar picker. [label] already carries the « +lus » suffix. */
data class FlagTabEntry(val tab: FlagTab, val label: String, val color: Color)

/**
 * Drapeaux search app bar (#603 PR2, ADR-017) — replaces the old `FlagsHeader` + text `PrimaryTabRow`
 * with the « façon Réglages » app bar of the vision:
 *
 * - **left** : a colored flag glyph for the CURRENT tab (its flag color), tappable → a tab picker
 *   dropdown. The picker keeps tab switching discoverable now that the text tab row is gone (swipe
 *   between tabs is preserved separately); re-selecting the current Cyan/DT tab toggles « +lus »
 *   exactly like the old re-tap, since it routes back through the same `onSelectTab`.
 * - **center** : a search pill that expands to an inline filter of the loaded flags
 *   ([filterFlagsByQuery]) — HFR has no server search, the flags are already in hand.
 * - **right** : the display-settings action (when configurable) + the shared account menu (whose
 *   avatar doubles as the « photo de profil » of the vision).
 *
 * The scroll-coupled translucency (elevated app bar + content gliding underneath) is intentionally
 * NOT wired here: it belongs to the deferred scroll-chrome work (cf. the hide-on-scroll deferral),
 * so PR2 keeps the simple top-of-column layout and ships the structure + interactions first.
 */
@Composable
@Suppress("LongParameterList") // App-bar API: state + 3 callbacks + optional action + the @Composable account slot.
fun FlagsSearchAppBar(
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onOpenViewSettings: (() -> Unit)?,
    accountMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlagTabPickerButton(
                currentTabColor = state.currentTabColor,
                tabs = state.tabs,
                onSelectTab = onSelectTab,
            )
            SearchField(
                state = SearchFieldState(state.searchEnabled, state.query, state.searchActive),
                onQueryChange = onQueryChange,
                onActiveChange = onSearchActiveChange,
                modifier = Modifier.weight(1f),
            )
            onOpenViewSettings?.let { open ->
                val viewSettingsLabel = stringResource(R.string.flags_view_settings_action)
                IconButton(
                    onClick = open,
                    modifier = Modifier.semantics { contentDescription = viewSettingsLabel },
                ) {
                    // U+FE0E pins the monochrome gear glyph (ForbiddenImport bans material icons),
                    // matching the legacy header's affordance until it migrates to the bottom-bar
                    // quick-config (#603 PR6).
                    Text(
                        text = "⚙︎",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            accountMenu()
        }
    }
}

/** State of [FlagsSearchAppBar]. [tabs] empty ⇒ the flag glyph is a static indicator (no picker). */
data class FlagsAppBarState(
    val currentTabColor: Color,
    val tabs: List<FlagTabEntry>,
    val searchEnabled: Boolean,
    val query: String,
    val searchActive: Boolean,
)

@Composable
private fun FlagTabPickerButton(
    currentTabColor: Color,
    tabs: List<FlagTabEntry>,
    onSelectTab: (FlagTab) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickerLabel = stringResource(R.string.flags_appbar_tab_picker)
    Box {
        IconButton(
            onClick = { if (tabs.isNotEmpty()) expanded = true },
            enabled = tabs.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = pickerLabel },
        ) {
            RedfaceVectorIcon(resId = CoreUiR.drawable.ic_ms_flag, tint = currentTabColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tabs.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { ColorDot(entry.color) },
                    onClick = {
                        expanded = false
                        onSelectTab(entry.tab)
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** State of the app-bar [SearchField]: whether searching is available, the query, and the open state. */
data class SearchFieldState(val enabled: Boolean, val query: String, val active: Boolean)

@Composable
private fun SearchField(
    state: SearchFieldState,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.flags_search_placeholder)
    val clearLabel = stringResource(R.string.flags_search_clear)
    val query = state.query
    Surface(
        onClick = { if (state.enabled) onActiveChange(true) },
        enabled = state.enabled,
        modifier = modifier.height(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(CoreUiR.drawable.ic_ms_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.active && state.enabled) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                IconButton(
                    onClick = {
                        onQueryChange("")
                        onActiveChange(false)
                    },
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = clearLabel },
                ) {
                    Text(text = "✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    text = query.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (query.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
