package fr.forumhfr.redface2.core.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import fr.forumhfr.redface2.core.ui.R

/**
 * #494 — the settings shell top bar with an activable search field, built on the Material 3 stable
 * [TopAppBar].
 *
 * - Normal mode: a back navigation icon (calls [onBack]), the [RedfaceSettingsSearchTopBarLabels.title],
 *   a search action icon (calls `onSearchActiveChange(true)`), then the caller's [actions] slot.
 * - Search mode: the navigation icon CLOSES the search (`onSearchActiveChange(false)`, it does NOT
 *   pop the route), the title becomes a single-line [TextField] bound to [query] / [onQueryChange],
 *   and a clear action (visible only when [query] is non-blank) empties the query.
 *
 * The two back paths are deliberately distinct: only the normal-mode back pops the route, so the
 * search can always be dismissed without leaving the screen.
 *
 * Icons use local vector drawables from `:core:ui` rendered with the material3 [Icon] (material-icons
 * are forbidden project-wide); a11y labels live on the [IconButton]s, the glyphs are decorative.
 *
 * [scrollBehavior] branche l'effet « contenu sous la barre » (idiome M3) : avec un
 * `pinnedScrollBehavior` câblé côté écran (Scaffold `nestedScroll`), la barre prend la teinte
 * `scrolledContainerColor` (`surfaceContainer`) dès que la liste de résultats défile dessous.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // Top-bar API: search state + the two back/query callbacks + scroll + slots.
fun RedfaceSettingsSearchTopBar(
    labels: RedfaceSettingsSearchTopBarLabels,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        navigationIcon = {
            // Search active → the icon CLOSES the search. Otherwise it's a back affordance, shown only
            // when [onBack] is provided : a top-level/root use (Réglages onglet, #494 v2) passes null so
            // no dead back button appears (Codex P2).
            if (searchActive) {
                NavigationIconButton(
                    label = labels.closeSearchContentDescription,
                    onClick = { onSearchActiveChange(false) },
                )
            } else if (onBack != null) {
                NavigationIconButton(
                    label = labels.backContentDescription,
                    onClick = onBack,
                )
            }
        },
        title = {
            if (searchActive) {
                // Auto-focus + open the keyboard as soon as the search field enters composition
                // (i.e. when search is activated): without this the field shows but stays unfocused.
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text(labels.searchPlaceholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Filtering is live; the IME "Search" action just dismisses the keyboard.
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            } else {
                Text(labels.title)
            }
        },
        actions = {
            if (searchActive) {
                if (query.isNotEmpty()) {
                    val clearLabel = labels.clearSearchContentDescription
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.semantics { contentDescription = clearLabel },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                        )
                    }
                }
            } else {
                val openLabel = labels.openSearchContentDescription
                IconButton(
                    onClick = { onSearchActiveChange(true) },
                    modifier = Modifier.semantics { contentDescription = openLabel },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                }
                actions()
            }
        },
    )
}

/** Bouton de navigation (flèche retour / fermer la recherche) partagé par le top bar de réglages. */
@Composable
private fun NavigationIconButton(label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = null,
        )
    }
}

/**
 * #494 — localized labels for [RedfaceSettingsSearchTopBar]. Passed as plain `String`s (resolved
 * via `stringResource` at the feature call site) so `:core:ui` carries no settings-specific strings
 * and stays reusable.
 */
data class RedfaceSettingsSearchTopBarLabels(
    val title: String,
    val searchPlaceholder: String,
    val backContentDescription: String,
    val openSearchContentDescription: String,
    val closeSearchContentDescription: String,
    val clearSearchContentDescription: String,
)
