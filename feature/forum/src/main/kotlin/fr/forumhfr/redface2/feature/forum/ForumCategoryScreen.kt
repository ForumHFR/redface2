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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicSummary

/**
 * Per-category screen: chip row of subcategories ("Toutes" + each subcat) on top, list
 * of [TopicSummary] below, with a basic "previous / next page" pager. Tapping a topic
 * fires [onOpenTopic] with the right `(cat, post, page, scrollTo)` triple — when the
 * authenticated payload exposes `lastPostReadId`, the user lands directly on their last
 * read position; otherwise [onOpenTopic] is called with `page = 1` and no scroll target.
 */
@Composable
fun ForumCategoryScreen(
    request: CategoryRequest,
    onOpenTopic: (TopicSummary) -> Unit,
) {
    val viewModel = hiltViewModel<CategoryViewModel, CategoryViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
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
                text = stringResource(R.string.category_title, state.cat),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            SubcategoryChips(
                state = state.subcategories,
                selectedSubcat = state.selectedSubcat,
                onSelect = viewModel::selectSubcategory,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TopicsBody(
                state = state.topics,
                onOpenTopic = onOpenTopic,
                onRetry = viewModel::refresh,
                onSelectPage = viewModel::selectPage,
                currentPage = state.page,
            )
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
                text = state.message ?: stringResource(R.string.category_subcategories_error),
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

@Composable
private fun TopicsBody(
    state: TopicsUiState,
    onOpenTopic: (TopicSummary) -> Unit,
    onRetry: () -> Unit,
    onSelectPage: (Int) -> Unit,
    currentPage: Int,
) {
    when (state) {
        TopicsUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is TopicsUiState.Error -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.category_topics_error),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (!state.message.isNullOrBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.forum_action_retry))
            }
        }

        is TopicsUiState.Content -> {
            val topics = state.page.topics
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(topics, key = TopicSummary::topicId) { topic ->
                    TopicRow(topic = topic, onClick = { onOpenTopic(topic) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    PagerRow(
                        currentPage = currentPage,
                        onSelectPage = onSelectPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicRow(
    topic: TopicSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (topic.hasUnread == true) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
            Text(
                text = stringResource(
                    R.string.category_topic_metadata,
                    topic.author,
                    topic.lastReplyAuthor,
                    topic.lastReplyAt,
                    topic.replyCount,
                    topic.totalPages,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (topic.isSticky || topic.isLocked) {
                Text(
                    text = topicBadgeText(topic),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun topicBadgeText(topic: TopicSummary): String {
    val sticky = stringResource(R.string.category_badge_sticky)
    val locked = stringResource(R.string.category_badge_locked)
    return when {
        topic.isSticky && topic.isLocked -> "$sticky · $locked"
        topic.isSticky -> sticky
        else -> locked
    }
}

@Composable
private fun PagerRow(
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            enabled = currentPage > 1,
            onClick = { onSelectPage(currentPage - 1) },
        ) {
            Text(text = stringResource(R.string.category_pager_previous))
        }
        Text(
            text = stringResource(R.string.category_pager_current, currentPage),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { onSelectPage(currentPage + 1) }) {
            Text(text = stringResource(R.string.category_pager_next))
        }
    }
}
