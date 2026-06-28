package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/** One selectable tab of the Drapeaux top-bar picker. [label] already carries the « +lus » suffix. */
data class FlagTabEntry(val tab: FlagTab, val label: String, val color: Color)

/**
 * State of [FlagsTopBar]. [tabs] empty ⇒ the flag glyph is a static indicator (anonymous, no picker).
 */
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
     * that have the toggle (Cyan / DT), `null` on the others (Red / Favori / Super). `true` lights the
     * eye indicator in the left container.
     */
    val readFilterShowsRead: Boolean?,
    /**
     * #661 — GLOBAL shape of the « +lus » cue: [PlusLusIndicatorStyle.Eye] (default, an eye glyph
     * capsule) or [PlusLusIndicatorStyle.Ring] (the flag dot drawn as a hollow coloured ring instead
     * of a filled disc). Only takes visual effect when [readFilterShowsRead] is `true`.
     */
    val plusLusIndicatorStyle: PlusLusIndicatorStyle = PlusLusIndicatorStyle.Eye,
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

private val ContainerShape = RoundedCornerShape(22.dp)
private const val INDICATOR_BG_ALPHA = 0.18f

/**
 * Drapeaux top bar (#603 / #665 / #661, ADR-017) — two rounded « containers » floating over a
 * transparent centre, replacing the old flat `FlagsSearchAppBar`:
 *
 * - **left** : the tab picker — the section's flag colour + the short tab name + the « +lus » eye
 *   indicator when read items are shown (Cyan / DT). Tapping opens the same dropdown as before (tab
 *   entries + the contextual « +lus » toggle + « Réglages d'affichage ») — those affordances are kept,
 *   the eye is only an additional *visible state*.
 * - **centre** : transparent ([Spacer] weight) — reserved for the deferred scroll-under-bar work (#665).
 * - **right** : a retractable search loupe (expands into a full field) + the round account avatar
 *   ([accountMenu], the « photo de profil » of the vision).
 *
 * The scroll-coupled translucency / content gliding underneath (#665) and the pull-to-refresh
 * choreography + redface loader are deferred to follow-up PRs; this PR ships the new bar look in the
 * existing top-of-column layout.
 */
@Composable
@Suppress("LongParameterList") // App-bar API: state + 4 callbacks + the @Composable account slot + modifier.
fun FlagsTopBar(
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onOpenViewSettings: () -> Unit,
    accountMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.searchActive && state.searchEnabled) {
            // Loupe expanded into a full-width field; the close (✕) collapses it (back to the containers).
            ExpandedSearchContainer(
                query = state.query,
                onQueryChange = onQueryChange,
                onClose = {
                    onQueryChange("")
                    onSearchActiveChange(false)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LeftContainer(
                state = state,
                onSelectTab = onSelectTab,
                onOpenViewSettings = onOpenViewSettings,
            )
            // Transparent centre — the list will glide underneath here once #665 lands.
            Spacer(Modifier.weight(1f))
            RightContainer(
                searchEnabled = state.searchEnabled,
                onOpenSearch = { onSearchActiveChange(true) },
                accountMenu = accountMenu,
            )
        }
    }
}

@Composable
private fun LeftContainer(
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onOpenViewSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasPicker = state.tabs.isNotEmpty()
    // Close the picker if the tab list vanishes (e.g. sign-out) while it is open (Codex nit).
    LaunchedEffect(hasPicker) { if (!hasPicker) expanded = false }
    // a11y (Codex) — announce the WHOLE pill: the current tab's FULL name + its « +lus » state, so
    // TalkBack states what the short visible label abbreviates. The « change tab » affordance rides on
    // onClickLabel, so it is offered ONLY when the picker exists (anonymous = a static indicator).
    val tabContentDescription = if (state.readFilterShowsRead == true) {
        stringResource(R.string.flags_appbar_current_tab_pluslus, flagFullTabName(state.currentTab))
    } else {
        stringResource(R.string.flags_appbar_current_tab, flagFullTabName(state.currentTab))
    }
    val changeTabLabel = stringResource(R.string.flags_appbar_change_tab)
    Box {
        Surface(
            shape = ContainerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(44.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(ContainerShape)
                    .then(
                        if (hasPicker) {
                            Modifier.clickable(role = Role.Button, onClickLabel = changeTabLabel) {
                                expanded = true
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = tabContentDescription }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // #661 — in « Ring » mode, the dot itself becomes a hollow coloured ring while « +lus »
                // is active (no extra glyph); in « Eye » mode it stays filled and the eye capsule shows.
                val showsRead = state.readFilterShowsRead == true
                val ringCue = showsRead && state.plusLusIndicatorStyle == PlusLusIndicatorStyle.Ring
                FlagDot(state.currentTabColor, ring = ringCue)
                Text(
                    text = flagShortTabName(state.currentTab),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showsRead && state.plusLusIndicatorStyle == PlusLusIndicatorStyle.Eye) {
                    PlusLusIndicator(state.currentTabColor)
                }
            }
        }
        TabPickerDropdown(
            expanded = expanded,
            onDismiss = { expanded = false },
            state = state,
            onSelectTab = onSelectTab,
            onOpenViewSettings = onOpenViewSettings,
        )
    }
}

/**
 * The section's flag « drapal ». Normally a filled disc; when [ring] is true (#661 « Ring » +lus cue)
 * it is drawn as a hollow coloured ring, so the dot itself carries the read-items state.
 */
@Composable
private fun FlagDot(color: Color, ring: Boolean = false) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .then(
                if (ring) {
                    Modifier.border(width = 3.dp, color = color, shape = CircleShape)
                } else {
                    Modifier.clip(CircleShape).background(color)
                },
            ),
    )
}

/** #661 — « +lus » active cue: an eye glyph in a tinted capsule (variant D, the default indicator). */
@Composable
private fun PlusLusIndicator(color: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = INDICATOR_BG_ALPHA))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        RedfaceVectorIcon(
            resId = CoreUiR.drawable.ic_ms_visibility,
            contentDescription = null,
            tint = color,
            size = 16.dp,
        )
    }
}

@Composable
private fun TabPickerDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: FlagsAppBarState,
    onSelectTab: (FlagTab) -> Unit,
    onOpenViewSettings: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        state.tabs.forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.label) },
                leadingIcon = { FlagDotSmall(entry.color) },
                onClick = {
                    onDismiss()
                    onSelectTab(entry.tab)
                },
            )
        }
        // #661 — discoverability: the « +lus » toggle (otherwise only reachable by re-tapping a tab) and
        // the display-settings sheet (otherwise only via the bottom-bar re-tap) get explicit entries.
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
                // Re-selecting the active Cyan/DT tab toggles its « +lus » filter — same path as a re-tap.
                onClick = {
                    onDismiss()
                    onSelectTab(state.currentTab)
                },
            )
        }
        if (state.searchEnabled) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.flags_appbar_menu_display_settings)) },
                onClick = {
                    onDismiss()
                    onOpenViewSettings()
                },
            )
        }
    }
}

@Composable
private fun FlagDotSmall(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun RightContainer(
    searchEnabled: Boolean,
    onOpenSearch: () -> Unit,
    accountMenu: @Composable () -> Unit,
) {
    Surface(
        shape = ContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchEnabled) {
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        painter = painterResource(CoreUiR.drawable.ic_ms_search),
                        contentDescription = stringResource(R.string.flags_search_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            accountMenu()
        }
    }
}

@Composable
private fun ExpandedSearchContainer(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearLabel = stringResource(R.string.flags_search_clear)
    Surface(
        modifier = modifier.height(48.dp),
        shape = ContainerShape,
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
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = clearLabel },
            ) {
                Text(text = "✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Short tab name for the compact left container (« Mes sujets » → « Mes », « Favoris » → « Fav »). */
@Composable
private fun flagShortTabName(tab: FlagTab): String = stringResource(
    when (tab) {
        FlagTab.Cyan -> R.string.flags_tab_my_topics_short
        FlagTab.Red -> R.string.flags_tab_read_only
        FlagTab.Favorite -> R.string.flags_tab_favorite_short
        FlagTab.Dt -> R.string.flags_tab_dt
        FlagTab.Super -> R.string.flags_tab_super
    },
)

/** Full tab name for the a11y content description (the short visible label is abbreviated). */
@Composable
private fun flagFullTabName(tab: FlagTab): String = stringResource(
    when (tab) {
        FlagTab.Cyan -> R.string.flags_tab_my_topics
        FlagTab.Red -> R.string.flags_tab_read_only
        FlagTab.Favorite -> R.string.flags_tab_favorite
        FlagTab.Dt -> R.string.flags_tab_dt
        FlagTab.Super -> R.string.flags_tab_super
    },
)
