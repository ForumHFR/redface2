package fr.forumhfr.redface2.feature.flags

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

// #665 — elevated translucency of the app bar container (mirrors RedfaceSearchAppBar's value): enough
// to read the bar yet let the content be glimpsed gliding behind it.
private const val FLAGS_APP_BAR_ELEVATED_ALPHA = 0.94f

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
 * - **right** : the shared account menu (whose avatar doubles as the « photo de profil » of the
 *   vision). The display-settings gear was retired (#603 polish) — the quick-config sheet is now
 *   opened by re-tapping the Drapeaux bottom-bar icon (#603 PR6), so a duplicate header trigger is gone.
 *
 * #665 — the bar is now SUPERPOSED over the list (edge-to-edge « content under the bar »): [elevated]
 * drives the scroll-coupled translucency exactly like [RedfaceSearchAppBar] — at rest it is opaque
 * `surface` (continuous with the content), and once the list has scrolled under it the container turns
 * to a lightly translucent `surfaceContainer` (the content is glimpsed gliding behind) with a 3dp shadow.
 * The status-bar inset is owned here (the Surface fill extends behind the status bar), since the bar is
 * no longer inside a Column that pads it.
 */
@Composable
@Suppress("LongParameterList") // App-bar API: state + 4 callbacks + the @Composable account slot + modifier + elevated.
fun FlagsSearchAppBar(
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onOpenViewSettings: () -> Unit,
    accountMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    // #665 — same elevation idiom as RedfaceSearchAppBar: translucent surfaceContainer + shadow when the
    // content scrolls under the bar, opaque surface at rest. The search pill keeps its own opaque tone.
    val containerColor by animateColorAsState(
        targetValue = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = FLAGS_APP_BAR_ELEVATED_ALPHA)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "flagsAppBarContainer",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (elevated) 3.dp else 0.dp,
        label = "flagsAppBarShadow",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shadowElevation = shadowElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // #665 — the bar owns its status-bar inset now (was on the parent Column); the Surface
                // fill thus extends behind the status bar for the edge-to-edge look.
                .statusBarsPadding()
                // Asymmetric vertical padding (#603, XaTriX preset D): the bottom is trimmed to 2.dp so
                // the category band tucks up close under the search bar; the top keeps 8.dp of breathing
                // room below the status bar.
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlagTabPickerButton(
                state = state,
                onSelectTab = onSelectTab,
                onOpenViewSettings = onOpenViewSettings,
            )
            SearchField(
                state = SearchFieldState(state.searchEnabled, state.query, state.searchActive),
                onQueryChange = onQueryChange,
                onActiveChange = onSearchActiveChange,
                modifier = Modifier.weight(1f),
            )
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
    /** #661 — the active tab, so the picker can offer the contextual « +lus » toggle. */
    val currentTab: FlagTab,
    /**
     * #661 — whether the active tab currently shows read items (« +lus ») : `true`/`false` on the tabs
     * that have the toggle (Cyan / DT), `null` on the others (Red / Favori / Super) → no « +lus » entry.
     */
    val readFilterShowsRead: Boolean?,
)

/**
 * #661 — read-filter state for the picker's contextual « +lus » entry: `null` when the active tab has
 * no such toggle (Red / Favori / Super), otherwise whether read items are currently shown (Cyan / DT).
 * Pure → unit-tested.
 */
internal fun flagsReadFilterShowsRead(
    tab: FlagTab,
    cyanShowsRead: Boolean,
    dtShowsRead: Boolean,
): Boolean? = when (tab) {
    FlagTab.Cyan -> cyanShowsRead
    FlagTab.Dt -> dtShowsRead
    else -> null
}

@Composable
private fun FlagTabPickerButton(
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onOpenViewSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickerLabel = stringResource(R.string.flags_appbar_tab_picker)
    Box {
        IconButton(
            onClick = { if (state.tabs.isNotEmpty()) expanded = true },
            enabled = state.tabs.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = pickerLabel },
        ) {
            // Flag glyph of the current tab, tinted with its color (cyan/red/favori/fuchsia). (The
            // pixel-art RF2 brand flag was rolled back — too crude at 24dp ; awaiting a proper vector.)
            RedfaceVectorIcon(resId = CoreUiR.drawable.ic_ms_flag, tint = state.currentTabColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.tabs.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { ColorDot(entry.color) },
                    onClick = {
                        expanded = false
                        onSelectTab(entry.tab)
                    },
                )
            }
            // #661 — discoverability: the « +lus » toggle (otherwise reachable only by re-tapping a tab)
            // and the display-settings sheet (otherwise only via the bottom-bar re-tap — the header gear
            // was retired, #648) get explicit entries under a divider.
            val showsRead = state.readFilterShowsRead
            if (showsRead != null || state.searchEnabled) {
                HorizontalDivider()
            }
            if (showsRead != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (showsRead) {
                                    R.string.flags_appbar_menu_hide_read
                                } else {
                                    R.string.flags_appbar_menu_show_read
                                },
                            ),
                        )
                    },
                    // Re-selecting the active Cyan/DT tab toggles its « +lus » filter — the same path as a
                    // tab re-tap (FlagsViewModel.selectTab -> handleReTap).
                    onClick = {
                        expanded = false
                        onSelectTab(state.currentTab)
                    },
                )
            }
            if (state.searchEnabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.flags_appbar_menu_display_settings)) },
                    onClick = {
                        expanded = false
                        onOpenViewSettings()
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
                    // No wrap in the search pill — a long query/placeholder stays on one line and
                    // ellipsises instead of pushing the bar to a second row.
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
