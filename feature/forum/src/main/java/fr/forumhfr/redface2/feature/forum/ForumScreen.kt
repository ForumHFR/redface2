package fr.forumhfr.redface2.feature.forum

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun ForumScreen(
    onOpenCategory: () -> Unit,
    onOpenTopic: () -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.forum_title),
        body = stringResource(R.string.forum_body),
    ) {
        Button(onClick = onOpenCategory) {
            Text(text = stringResource(R.string.forum_open_category))
        }
        OutlinedButton(onClick = onOpenTopic) {
            Text(text = stringResource(R.string.forum_open_topic))
        }
    }
}

@Composable
fun CategoryScreen(
    cat: Int,
    subcat: Int?,
    onOpenTopic: () -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.category_title, cat),
        body = if (subcat == null) {
            stringResource(R.string.category_body_without_subcat)
        } else {
            stringResource(R.string.category_body_with_subcat, subcat)
        },
    ) {
        Button(onClick = onOpenTopic) {
            Text(text = stringResource(R.string.category_open_topic))
        }
    }
}
