package fr.forumhfr.redface2.feature.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Category

/**
 * Forum home tab. Lists the 19 public HFR categories from the REST API. Tapping a row
 * delegates to [onOpenCategory] which lifts the user into the per-category screen.
 *
 * No SwipeRefresh / Accompanist — Phase 1C-A keeps the same minimal "Réessayer" /
 * "Actualiser" affordance the Drapeaux screen ships with, per ADR-003 § "UI" guidance.
 */
@Composable
fun ForumScreen(
    onOpenCategory: (Category) -> Unit,
) {
    val viewModel: ForumViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.forum_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            when (val current = state) {
                ForumUiState.Loading -> ForumLoading()
                is ForumUiState.Error -> ForumError(
                    message = current.message,
                    onRetry = viewModel::refresh,
                )

                is ForumUiState.Content -> ForumContent(
                    categories = current.categories,
                    onOpenCategory = onOpenCategory,
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun ForumLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ForumError(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.forum_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.forum_action_retry))
        }
    }
}

@Composable
private fun ForumContent(
    categories: List<Category>,
    onOpenCategory: (Category) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.forum_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRefresh) {
                Text(text = stringResource(R.string.forum_action_refresh))
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(categories, key = { it.id }) { category ->
            CategoryRow(category = category, onClick = { onOpenCategory(category) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onRefresh) {
                    Text(text = stringResource(R.string.forum_action_refresh))
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    onClick: () -> Unit,
) {
    val accessibilityLabel = stringResource(
        R.string.forum_category_accessibility,
        category.name,
        category.subcategoryCount,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.85f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.forum_category_subcat_count,
                    category.subcategoryCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
