package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.postContentExcerpt
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * #783 — HFR-native reverse-citation list. The title always reflects the target post's server
 * counter; the body renders exactly the distinct rows returned by `quote_only=1` and never labels
 * their size as a second count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CitingPostsSheet(
    state: CitingPostsSheetState,
    onDismiss: () -> Unit,
    onPostClick: (Post) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.topic_citing_posts_title,
                    state.citedCount,
                    state.citedCount,
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val content = state.content) {
                CitingPostsSheetContent.Idle,
                CitingPostsSheetContent.Loading,
                -> CitingPostsLoading()

                is CitingPostsSheetContent.Loaded -> CitingPostsList(
                    posts = content.posts,
                    onPostClick = onPostClick,
                )

                CitingPostsSheetContent.Empty -> CitingPostsMessage(
                    text = stringResource(R.string.topic_citing_posts_empty),
                )

                is CitingPostsSheetContent.Error -> CitingPostsMessage(
                    text = citingPostsErrorLabel(content.kind),
                )
            }
        }
    }
}

@Composable
private fun CitingPostsLoading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CitingPostsList(
    posts: List<Post>,
    onPostClick: (Post) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
    ) {
        items(items = posts, key = { post -> post.numreponse }) { post ->
            CitingPostRow(post = post, onClick = { onPostClick(post) })
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun CitingPostRow(post: Post, onClick: () -> Unit) {
    val rawExcerpt = remember(post.content) { postContentExcerpt(post.content) }
    val excerpt = if (rawExcerpt.isNotBlank()) {
        rawExcerpt
    } else {
        stringResource(R.string.topic_citing_posts_no_text)
    }
    val clickLabel = stringResource(R.string.topic_citing_posts_open, post.author)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = clickLabel,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = post.author,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = citingPostDateTimeFormatter.format(post.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = excerpt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CitingPostsMessage(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun citingPostsErrorLabel(kind: HfrErrorKind): String {
    val shared = kind.sharedLabelResOrNull()
    return if (shared != null) {
        stringResource(shared)
    } else {
        stringResource(R.string.topic_citing_posts_error)
    }
}

// #783 (gate Fable R3) — citations span years, so an HH:mm-only stamp is ambiguous (all rows read as
// today). Show the compact HFR-style date + time (« dd/MM/yyyy à HH:mm ») in Europe/Paris, the forum's
// wall-clock zone.
private val citingPostDateTimeFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))
