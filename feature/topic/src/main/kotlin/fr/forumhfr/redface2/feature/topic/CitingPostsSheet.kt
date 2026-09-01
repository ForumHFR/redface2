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
import androidx.compose.material3.rememberModalBottomSheetState
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
import fr.forumhfr.redface2.core.ui.sheet.clampSheetTopOverscroll
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
    // #1193 — force skipPartiallyExpanded: the tall, scrollable citer list otherwise anchors at
    // M3's PartiallyExpanded and its underdamped settle overshoots the top butée on opening. Same
    // fix as QuickReplySheet / MessageEditorComponents.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // #1193 residual — swallow the upward overscroll left over at the Expanded anchor
                // so the M3 spring settle cannot overshoot above it. Sits on the content wrapper
                // (closest nested-scroll parent to the CitingPostsList LazyColumn), not the sheet.
                .clampSheetTopOverscroll(sheetState)
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
            .heightIn(min = CITING_POSTS_CONTENT_MIN_HEIGHT),
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
            // #1193 — reserve the same 180 dp minimum as the Loading / Empty / Error states so a
            // short result does not shrink the sheet below the Loading placeholder when
            // Loading→Loaded lands mid-open (which would move the top anchor and re-run the M3
            // settle). A long list still grows up to the max — Fix 1 (skipPartiallyExpanded) is
            // the primary guard against the opening oscillation.
            .heightIn(min = CITING_POSTS_CONTENT_MIN_HEIGHT, max = CITING_POSTS_LIST_MAX_HEIGHT),
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
            .heightIn(min = CITING_POSTS_CONTENT_MIN_HEIGHT)
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

// #1193 — a common minimum height across the Loading / Loaded / Empty / Error states so a short
// Loaded result does not shrink the sheet (and re-run the M3 settle) relative to the Loading
// placeholder. A long Loaded list still grows up to CITING_POSTS_LIST_MAX_HEIGHT.
private val CITING_POSTS_CONTENT_MIN_HEIGHT = 180.dp
private val CITING_POSTS_LIST_MAX_HEIGHT = 520.dp

// #783 (gate Fable R3) — citations span years, so an HH:mm-only stamp is ambiguous (all rows read as
// today). Show the compact HFR-style date + time (« dd/MM/yyyy à HH:mm ») in Europe/Paris, the forum's
// wall-clock zone.
private val citingPostDateTimeFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))
