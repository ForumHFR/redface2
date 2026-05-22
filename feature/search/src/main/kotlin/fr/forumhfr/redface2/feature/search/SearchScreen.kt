package fr.forumhfr.redface2.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchTopicResult

/**
 * Phase 2G-A (#150 partiel) — search tab screen.
 *
 * Sober single-field UX : type a query, tap « Rechercher » (or press IME action),
 * scroll the result cards, tap one to open the topic. When the query matched
 * multiple HFR categories, a chip row at the top lets the user re-scope.
 *
 * The [onOpenTopic] callback receives a [SearchTopicResult] and is responsible
 * for pushing the matching `TopicRoute` onto the back stack — the screen itself
 * has no knowledge of the nav graph.
 */
@Composable
fun SearchScreen(
    onOpenTopic: (SearchTopicResult) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        onIntent = viewModel::submit,
        onOpenTopic = onOpenTopic,
        modifier = modifier,
    )
}

@Composable
internal fun SearchContent(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    onOpenTopic: (SearchTopicResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.search_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SearchField(
                query = state.query,
                isSubmitEnabled = !state.isLoading && state.query.isNotBlank(),
                onQueryChange = { onIntent(SearchIntent.QueryChanged(it)) },
                onSubmit = { onIntent(SearchIntent.Submit) },
            )
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

@Composable
private fun SearchField(
    query: String,
    isSubmitEnabled: Boolean,
    onQueryChange: (String) -> Unit,
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
    // Non-Lazy `Column`-of-`Row`s chunked 3-by-3 : the pivot tops out at ~18 entries
    // (one per public HFR category) so virtualization is unnecessary. Using a
    // `LazyColumn` here would also conflict with the outer non-Lazy `Column` parent
    // and crash at measurement time (two unbounded vertical scrollers can't coexist
    // without an explicit weight/height constraint).
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_pivot_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pivot.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                line.forEach { entry ->
                    AssistChip(
                        onClick = { onSelect(entry) },
                        label = { Text(entry.label) },
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
private fun ErrorState(kind: SearchErrorKind, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val messageResId = when (kind) {
        SearchErrorKind.Network -> R.string.search_error_network
        SearchErrorKind.Unknown -> R.string.search_error_unknown
    }
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
                    result.lastReplyAt,
                    result.lastReplyAuthor,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
