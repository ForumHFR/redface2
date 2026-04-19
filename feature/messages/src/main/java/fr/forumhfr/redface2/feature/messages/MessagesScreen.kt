package fr.forumhfr.redface2.feature.messages

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun MessagesScreen(
    onOpenTopic: () -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.messages_title),
        body = stringResource(R.string.messages_body),
    ) {
        Button(onClick = onOpenTopic) {
            Text(text = stringResource(R.string.messages_open_topic))
        }
    }
}
