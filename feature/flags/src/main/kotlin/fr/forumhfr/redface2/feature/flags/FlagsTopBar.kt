package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
// #603/#665 — shared height of every top-bar container AND the expanded search field, so entering
// search never changes the bar height (no content shift underneath, XaTriX dogfood).
private val ContainerHeight = 44.dp
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

/**
 * #603/#665 contract (XaTriX) — the left container is ONE visual Surface holding TWO distinct
 * interactive zones (no parent click, Codex):
 *
 * - **flag zone** (the section's coloured flag glyph) opens the quick menu — « which type + change it ».
 * - **type zone** (short type name + the « +lus » indicator) toggles « +lus » directly on a tap, for
 *   the tabs that have it (Cyan / DT). For the others it is plain, non-interactive text.
 */
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
    Box {
        Surface(
            shape = ContainerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(ContainerHeight),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagGlyphZone(
                    color = state.currentTabColor,
                    enabled = hasPicker,
                    fullTabName = flagFullTabName(state.currentTab),
                    onOpenMenu = { expanded = true },
                )
                TypePlusLusZone(
                    tab = state.currentTab,
                    color = state.currentTabColor,
                    showsRead = state.readFilterShowsRead,
                    indicatorStyle = state.plusLusIndicatorStyle,
                    // « +lus » toggle = re-select the active Cyan/DT tab (same path as the legacy re-tap).
                    onToggle = { onSelectTab(state.currentTab) },
                )
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
 * Zone 1 — the section's flag glyph; a button that opens the quick menu. The glyph is the
 * Material flag icon tinted with the active type colour (the « drapal » reprise, XaTriX). When there
 * is no picker (anonymous) it is a static, non-interactive indicator.
 */
@Composable
private fun FlagGlyphZone(
    color: Color,
    enabled: Boolean,
    fullTabName: String,
    onOpenMenu: () -> Unit,
) {
    val openMenuLabel = stringResource(R.string.flags_appbar_open_menu)
    val description = stringResource(R.string.flags_appbar_current_tab, fullTabName)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 48.dp)
            .clip(ContainerShape)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClickLabel = openMenuLabel, onClick = onOpenMenu)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        RedfaceVectorIcon(
            resId = CoreUiR.drawable.ic_ms_flag,
            contentDescription = null,
            tint = color,
            size = 20.dp,
        )
    }
}

/**
 * Zone 2 — the active type's short name plus the « +lus » indicator. A tap toggles « +lus » directly
 * for the tabs that support it ([showsRead] non-null = Cyan / DT); for the others ([showsRead] null:
 * Lu / Favori / Super) it is plain text with no action, no indicator (Codex: « pas d'action morte »).
 */
@Composable
private fun TypePlusLusZone(
    tab: FlagTab,
    color: Color,
    showsRead: Boolean?,
    indicatorStyle: PlusLusIndicatorStyle,
    onToggle: () -> Unit,
) {
    val togglable = showsRead != null
    val toggleLabel = stringResource(
        if (showsRead == true) R.string.flags_appbar_menu_hide_read else R.string.flags_appbar_menu_show_read,
    )
    val description = if (showsRead == true) {
        stringResource(R.string.flags_appbar_current_tab_pluslus, flagFullTabName(tab))
    } else {
        stringResource(R.string.flags_appbar_current_tab, flagFullTabName(tab))
    }
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(ContainerShape)
            .then(
                if (togglable) {
                    Modifier.clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = flagShortTabName(tab),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showsRead == true) {
            PlusLusIndicator(color = color, style = indicatorStyle)
        }
    }
}

/**
 * #661 — the « +lus » active cue, in zone 2 (Codex: the indicator lives here, not on the flag glyph):
 * [PlusLusIndicatorStyle.Eye] = an eye glyph in a tinted capsule (default), [PlusLusIndicatorStyle.Ring]
 * = a small hollow coloured ring.
 */
@Composable
private fun PlusLusIndicator(color: Color, style: PlusLusIndicatorStyle) {
    when (style) {
        PlusLusIndicatorStyle.Eye -> Box(
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

        PlusLusIndicatorStyle.Ring -> Box(
            modifier = Modifier
                .size(14.dp)
                .border(width = 3.dp, color = color, shape = CircleShape),
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
        // #603 (XaTriX) restyle — label on the LEFT, the type's coloured flag glyph TRAILING on the
        // right (cleaner « modern menu » look). The « Afficher les lus » entry was removed: the toggle
        // now lives directly on the left container's type zone, so a menu entry would just duplicate it.
        state.tabs.forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.label) },
                trailingIcon = {
                    RedfaceVectorIcon(
                        resId = CoreUiR.drawable.ic_ms_flag,
                        contentDescription = null,
                        tint = entry.color,
                        size = 18.dp,
                    )
                },
                onClick = {
                    onDismiss()
                    onSelectTab(entry.tab)
                },
            )
        }
        if (state.searchEnabled) {
            HorizontalDivider()
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
private fun RightContainer(
    searchEnabled: Boolean,
    onOpenSearch: () -> Unit,
    accountMenu: @Composable () -> Unit,
) {
    Surface(
        shape = ContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.height(ContainerHeight),
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
        // Same height as the containers so opening search never grows the bar (no shift below, #603).
        modifier = modifier.height(ContainerHeight),
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
