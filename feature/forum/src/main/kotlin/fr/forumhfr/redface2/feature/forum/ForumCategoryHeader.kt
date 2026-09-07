package fr.forumhfr.redface2.feature.forum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/** #1303 — the active search has a stable composition slot outside the animated commands. */
@Composable
internal fun ForumCategoryHeader(state: CategoryUiState, callbacks: ForumCategoryCallbacks) {
    Column {
        CategoryTitle(state, callbacks)
        if (state.layoutPreferencesReady && state.menusCollapsed) {
            ActiveFiltersSummary(state, callbacks.onSetMenusCollapsed)
        }
        AnimatedVisibility(
            visible = state.layoutPreferencesReady && !state.menusCollapsed,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column {
                SubcategoryChips(state.subcategories, state.selectedSubcat, callbacks.onSelectSubcategory)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.canCreateTopic) {
                    FlagFilterSelector(state.flagFilter, callbacks.onSelectFlagFilter)
                }
                if (!state.searchActive) SearchPill(onOpenSearch = callbacks.onOpenSearch)
            }
        }
        if (state.layoutPreferencesReady && state.searchActive) {
            ActiveSearchField(state.searchQuery, callbacks.onQueryChange, callbacks.onCloseSearch)
        }
    }
}

@Composable
private fun CategoryTitle(state: CategoryUiState, callbacks: ForumCategoryCallbacks) {
    val toggleLabel = stringResource(
        if (state.menusCollapsed) R.string.category_menus_expand else R.string.category_menus_collapse,
    )
    val expandedState = stringResource(
        if (state.menusCollapsed) R.string.category_layout_collapsed else R.string.category_layout_expanded,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.categoryName ?: stringResource(R.string.category_title_fallback, state.cat),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.menusCollapsed) {
            val searchLabel = stringResource(R.string.category_search_open)
            IconButton(
                onClick = callbacks.onOpenSearch,
                enabled = state.layoutPreferencesReady,
                modifier = Modifier.size(48.dp).semantics { contentDescription = searchLabel },
            ) {
                RedfaceVectorIcon(CoreUiR.drawable.ic_search)
            }
        }
        IconButton(
            onClick = { callbacks.onSetMenusCollapsed(!state.menusCollapsed) },
            enabled = state.layoutPreferencesReady,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = toggleLabel
                stateDescription = expandedState
            },
        ) {
            RedfaceVectorIcon(
                if (state.menusCollapsed) CoreUiR.drawable.ic_expand_more else CoreUiR.drawable.ic_expand_less,
            )
        }
    }
}

/** A selected subcategory remains identifiable even while its name cannot be loaded. */
@Composable
private fun ActiveFiltersSummary(state: CategoryUiState, onSetMenusCollapsed: (Boolean) -> Unit) {
    val subcategory = state.selectedSubcat?.let { id ->
        (state.subcategories as? SubcategoriesUiState.Content)?.subcategories?.find { it.id == id }?.name
            ?: stringResource(R.string.category_subcategory_fallback, id)
    }
    val filter = if (state.flagFilter != CategoryFlagFilter.ALL) {
        stringResource(state.flagFilter.labelRes())
    } else {
        null
    }
    val summary = listOfNotNull(subcategory, filter).joinToString(" · ")
    if (summary.isEmpty()) return
    val expandLabel = stringResource(R.string.category_menus_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = expandLabel) { onSetMenusCollapsed(false) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun CategoryFlagFilter.labelRes(): Int = when (this) {
    CategoryFlagFilter.ALL -> R.string.category_flag_filter_all
    CategoryFlagFilter.PARTICIPATED -> R.string.category_flag_filter_participated
    CategoryFlagFilter.READ -> R.string.category_flag_filter_read
    CategoryFlagFilter.FAVORITES -> R.string.category_flag_filter_favorites
}

/** Closed-state affordance: a pill that opens the search, styled like the settings search bar. */
@Composable
private fun SearchPill(onOpenSearch: () -> Unit) {
    val openLabel = stringResource(R.string.category_search_open)
    Surface(
        onClick = onOpenSearch,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(56.dp)
            .semantics { contentDescription = openLabel },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RedfaceVectorIcon(
                resId = CoreUiR.drawable.ic_search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.category_search_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Open-state field: back arrow closes, clear cross empties the query, autofocus + IME Search. */
@Composable
private fun ActiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Auto-focus + open the keyboard as soon as the field enters composition (i.e. when the
    // search is activated): without this the field shows but stays unfocused.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        label = { Text(stringResource(R.string.category_search_label)) },
        placeholder = { Text(stringResource(R.string.category_search_placeholder)) },
        leadingIcon = {
            val closeLabel = stringResource(R.string.category_search_close)
            IconButton(
                onClick = onCloseSearch,
                modifier = Modifier.semantics { contentDescription = closeLabel },
            ) {
                RedfaceVectorIcon(
                    resId = CoreUiR.drawable.ic_arrow_back,
                    contentDescription = null,
                )
            }
        },
        trailingIcon = {
            // Clear is offered only when there is something to clear; it empties the query but
            // leaves the search open (an open, empty field is a valid state).
            if (query.isNotEmpty()) {
                val clearLabel = stringResource(R.string.category_search_clear)
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = clearLabel },
                ) {
                    RedfaceVectorIcon(
                        resId = CoreUiR.drawable.ic_close,
                        contentDescription = null,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Filtering is live; the IME "Search" action just dismisses the keyboard (no close).
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/**
 * #455 — single-choice segmented row replicating the web `owntopic` toolbar: Tous /
 * Participé / Lus / Favoris. No Material icon (detekt `ForbiddenImport` bans
 * `androidx.compose.material.*`), labels only.
 */
@Composable
private fun FlagFilterSelector(
    selected: CategoryFlagFilter,
    onSelect: (CategoryFlagFilter) -> Unit,
) {
    val options = listOf(
        CategoryFlagFilter.ALL to R.string.category_flag_filter_all,
        CategoryFlagFilter.PARTICIPATED to R.string.category_flag_filter_participated,
        CategoryFlagFilter.READ to R.string.category_flag_filter_read,
        CategoryFlagFilter.FAVORITES to R.string.category_flag_filter_favorites,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (mode, labelRes) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(text = stringResource(labelRes), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SubcategoryChips(
    state: SubcategoriesUiState,
    selectedSubcat: Int?,
    onSelect: (Int?) -> Unit,
) {
    when (state) {
        SubcategoriesUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CircularProgressIndicator()
            }
        }

        is SubcategoriesUiState.Error -> {
            Text(
                // #324 — ServerDown / Network render the shared :core:ui label; Other
                // keeps the pre-existing rendering (raw message, generic fallback).
                text = state.kind.sharedLabelResOrNull()?.let { stringResource(it) }
                    ?: state.message
                    ?: stringResource(R.string.category_subcategories_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        is SubcategoriesUiState.Content -> {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedSubcat == null,
                        onClick = { onSelect(null) },
                        label = { Text(stringResource(R.string.category_filter_all)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
                items(state.subcategories, key = SubCategory::id) { subcategory ->
                    FilterChip(
                        selected = selectedSubcat == subcategory.id,
                        onClick = { onSelect(subcategory.id) },
                        label = { Text(subcategory.name) },
                    )
                }
            }
        }
    }
}

