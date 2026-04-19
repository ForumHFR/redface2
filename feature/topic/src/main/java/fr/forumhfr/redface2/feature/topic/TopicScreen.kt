package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun TopicScreen(
    cat: Int,
    post: Int,
    page: Int,
    scrollTo: Int?,
    onReply: (Int) -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.topic_title, post),
        body = stringResource(R.string.topic_body, cat, page),
    ) {
        scrollTo?.let {
            Text(text = stringResource(R.string.topic_scroll_to, it))
        }
        Button(onClick = { onReply(post) }) {
            Text(text = stringResource(R.string.topic_reply))
        }
    }
}
