package fr.forumhfr.redface2.feature.search

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun SearchScreen(
    onOpenResult: () -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.search_title),
        body = stringResource(R.string.search_body),
    ) {
        Button(onClick = onOpenResult) {
            Text(text = stringResource(R.string.search_open_result))
        }
    }
}
