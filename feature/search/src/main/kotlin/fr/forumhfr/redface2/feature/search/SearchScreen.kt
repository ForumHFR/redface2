package fr.forumhfr.redface2.feature.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.model.search.SearchTopicResult
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.formatLastReplyTimestamp

/**
 * Phase 2G-A/B (#150 partiel) — search tab screen.
 *
 * Sober single-field UX : type a query, tap « Rechercher » (or press IME action),
 * scroll the result cards, tap one to open the topic. When the query matched
 * multiple HFR categories, a chip row at the top lets the user re-scope.
 *
 * Tapping a result goes through the ViewModel ([SearchIntent.OpenResult]) which
 * resolves the result's REAL topic page when it carries a matched `numreponse`
 * (#277 — HFR's search hrefs always say `page=1`), then emits
 * [SearchEffect.NavigateToTopic]. [onOpenTopic] receives the FINAL navigation
 * values `(cat, post, page, scrollTo)` and is responsible for pushing the
 * matching `TopicRoute` onto the back stack — the screen itself has no
 * knowledge of the nav graph.
 */
@Composable
fun SearchScreen(
    onOpenTopic: (cat: Int, post: Int, page: Int, scrollTo: Int?) -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    initialPseudo: String? = null,
    onBack: (() -> Unit)? = null,
) {
    // AssistedInject (same pattern as ProfileRoute) : `initialPseudo` is only known at
    // construction time. The nav-entry-scoped ViewModelStore already gives the profile
    // entry point its own instance, distinct from the search tab's idle one.
    val viewModel = hiltViewModel<SearchViewModel, SearchViewModel.Factory>(
        creationCallback = { factory -> factory.create(initialPseudo) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Same one-shot collection pattern as TopicScreen : the Channel-backed flow
    // delivers each navigation effect exactly once.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SearchEffect.NavigateToTopic ->
                    onOpenTopic(effect.cat, effect.post, effect.page, effect.scrollTo)
            }
        }
    }
    SearchContent(
        state = state,
        onIntent = viewModel::submit,
        onOpenTopic = { result -> viewModel.submit(SearchIntent.OpenResult(result)) },
        modifier = modifier,
        topBarActions = topBarActions,
        onBack = onBack,
    )
}

@Suppress("LongParameterList") // Screen surface : state + intent sink + 2 nav callbacks + chrome slots.
@Composable
internal fun SearchContent(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    onOpenTopic: (SearchTopicResult) -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Header padding matches the other three main screens (24/12, headlineMedium) —
            // dogfooding feedback on v102: Recherche inherited the content's tighter 16dp
            // gutter, so its title and the account avatar sat visibly offset from
            // Drapeaux / Forum / Messages.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Profile entry point (« Derniers messages ») : the screen is pushed
                    // onto the current tab's back stack instead of living at the search
                    // tab root, so it needs its own back affordance. Same dp-sized vector
                    // as ProfileScreen (material-icons is detekt-banned).
                    if (onBack != null) {
                        val backLabel = stringResource(R.string.search_back)
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = backLabel },
                        ) {
                            Icon(
                                painter = painterResource(
                                    fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                topBarActions?.invoke()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // #433 — once a search is launched, the form gives the screen back to the
                // results : a one-line criteria banner replaces the two fields + scope
                // chips, and tapping it (or « Modifier ») re-expands the full form. The
                // pivot chips stay visible in both modes — re-scoping is a consultation
                // action on the CURRENT results, not an edit of the criteria.
                if (state.formCollapsed) {
                    CollapsedCriteriaBanner(
                        state = state,
                        onEdit = { onIntent(SearchIntent.EditCriteria) },
                    )
                } else {
                    SearchField(
                        query = state.query,
                        pseudo = state.pseudo,
                        // HFR accepts query-only, author-only, and combined searches.
                        isSubmitEnabled = !state.isLoading &&
                            (state.query.isNotBlank() || state.pseudo.isNotBlank()),
                        onQueryChange = { onIntent(SearchIntent.QueryChanged(it)) },
                        onPseudoChange = { onIntent(SearchIntent.PseudoChanged(it)) },
                        onSubmit = { onIntent(SearchIntent.Submit) },
                    )
                    SearchOptions(
                        textScope = state.textScope,
                        onTextScopeSelected = { onIntent(SearchIntent.TextScopeSelected(it)) },
                    )
                }
                if (state.pivotCategories.isNotEmpty()) {
                    PivotChips(
                        pivot = state.pivotCategories,
                        selected = state.selectedCategory,
                        onSelect = { onIntent(SearchIntent.CategorySelected(it)) },
                    )
                }
                HorizontalDivider()
                // `Modifier.weight(1f)` is required so the inner `LazyColumn` (in
                // `ResultsList`) gets a bounded vertical constraint. Without it, the
                // child `LazyColumn` measures with `Constraints.Infinity` and Compose
                // throws `IllegalStateException: Vertically scrollable component was
                // measured with an infinite max constraints`.
                SearchBody(
                    state = state,
                    onRetry = { onIntent(SearchIntent.Retry) },
                    onOpenTopic = onOpenTopic,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * #433 — compact summary of the launched search : `« query » · par pseudo · scope`,
 * single line, the whole card is the « edit » affordance. The scope segment only
 * shows when it differs from the default (no noise on the nominal case).
 */
@Composable
private fun CollapsedCriteriaBanner(
    state: SearchUiState,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = collapsedCriteriaSummary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.search_criteria_edit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun collapsedCriteriaSummary(state: SearchUiState): String {
    val parts = buildList {
        if (state.query.isNotBlank()) {
            add(stringResource(R.string.search_criteria_query, state.query))
        }
        if (state.pseudo.isNotBlank()) {
            add(stringResource(R.string.search_criteria_author, state.pseudo))
        }
        if (state.textScope != SearchTextScope.TitlesAndPosts) {
            add(stringResource(state.textScope.labelResId()))
        }
    }
    return parts.joinToString(separator = " · ")
}

@Composable
private fun SearchOptions(
    textScope: SearchTextScope,
    onTextScopeSelected: (SearchTextScope) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_scope_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                SearchTextScope.TitlesAndPosts,
                SearchTextScope.TitlesOnly,
                SearchTextScope.PostsOnly,
            ).forEach { scope ->
                FilterChip(
                    selected = scope == textScope,
                    onClick = { onTextScopeSelected(scope) },
                    label = { Text(text = stringResource(scope.labelResId())) },
                )
            }
        }
        Text(
            text = stringResource(R.string.search_future_filters_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("LongParameterList") // Two text fields + their change callbacks + the shared submit pair.
@Composable
private fun SearchField(
    query: String,
    pseudo: String,
    isSubmitEnabled: Boolean,
    onQueryChange: (String) -> Unit,
    onPseudoChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.search_field_label)) },
            placeholder = { Text(stringResource(R.string.search_field_placeholder)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (isSubmitEnabled) onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pseudo,
            onValueChange = onPseudoChange,
            singleLine = true,
            label = { Text(stringResource(R.string.search_pseudo_label)) },
            placeholder = { Text(stringResource(R.string.search_pseudo_placeholder)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (isSubmitEnabled) onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSubmit,
            enabled = isSubmitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.search_submit))
        }
    }
}

@Composable
private fun PivotChips(
    pivot: List<SearchPivotCategory>,
    selected: SearchPivotCategory?,
    onSelect: (SearchPivotCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_pivot_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.search_pivot_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // #188: long HFR category labels can otherwise wrap into unreadable vertical chips
        // on narrow screens. Keep the pivot rail horizontal and each chip single-line.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
        ) {
            items(items = pivot, key = { it.id }) { entry ->
                AssistChip(
                    onClick = { onSelect(entry) },
                    label = {
                        Text(
                            text = entry.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.sizeIn(maxWidth = 220.dp),
                    colors = if (entry.id == selected?.id) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchBody(
    state: SearchUiState,
    onRetry: () -> Unit,
    onOpenTopic: (SearchTopicResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier)
        state.errorMessage != null -> ErrorState(kind = state.errorMessage, onRetry = onRetry, modifier = modifier)
        !state.hasSearched -> IdleState(modifier = modifier)
        state.results.isEmpty() -> EmptyState(modifier = modifier)
        else -> ResultsList(results = state.results, onOpenTopic = onOpenTopic, modifier = modifier)
    }
}

@Composable
private fun IdleState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.search_idle_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.search_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.search_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ErrorState(kind: HfrErrorKind, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    // #324 — shared :core:ui labels for an HFR 5xx outage / a connectivity cut;
    // Other keeps the feature's generic message.
    val messageResId = kind.sharedLabelResOrNull() ?: R.string.search_error_unknown
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text(
            text = stringResource(messageResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.search_retry))
        }
    }
}

@Composable
private fun ResultsList(
    results: List<SearchTopicResult>,
    onOpenTopic: (SearchTopicResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        // Composite key (cat, topicId) — `topicId` alone is unique per HFR cat, so
        // two pivot categories COULD theoretically expose the same id (extremely
        // rare in practice but the parser doesn't guarantee uniqueness across cats).
        modifier = modifier,
    ) {
        items(items = results, key = { "${it.cat}_${it.topicId}" }) { result ->
            SearchResultCard(result = result, onClick = { onOpenTopic(result) })
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchTopicResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (result.isLocked) {
                    Text(
                        text = stringResource(R.string.search_result_locked),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.search_result_location, result.locationLabel()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.matchedExcerpt?.let { excerpt ->
                Text(
                    text = stringResource(R.string.search_result_excerpt, excerpt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.search_result_author, result.author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.search_result_replies, result.replyCount, result.viewCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.search_result_last_reply,
                    formatLastReplyTimestamp(result.lastReplyAt),
                    result.lastReplyAuthor,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SearchTextScope.labelResId(): Int = when (this) {
    SearchTextScope.TitlesAndPosts -> R.string.search_scope_titles_and_posts
    SearchTextScope.TitlesOnly -> R.string.search_scope_titles_only
    SearchTextScope.PostsOnly -> R.string.search_scope_posts_only
}

private fun SearchTopicResult.locationLabel(): String =
    listOfNotNull(categorySlug, subcategorySlug)
        .joinToString(separator = " / ")
        .ifBlank { "cat=$cat" }
