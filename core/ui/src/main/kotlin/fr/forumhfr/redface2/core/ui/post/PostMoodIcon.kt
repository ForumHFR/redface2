package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.HFR_MESSAGE_ICON_BASE_URL
import fr.forumhfr.redface2.core.ui.R

/**
 * #340 — compact HFR message-tone icon shown inline with a topic post's date. The caller already
 * filters the neutral `icon1`; any legacy value greater than 16 remains renderable on read.
 */
@Composable
fun PostMoodIcon(
    n: Int,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = "$HFR_MESSAGE_ICON_BASE_URL$n.gif",
        contentDescription = stringResource(R.string.post_mood_icon_description),
        modifier = modifier.size(POST_MOOD_ICON_SIZE),
    )
}

/** Native-sized footprint used by HFR for the post-number mood sprite. */
private val POST_MOOD_ICON_SIZE: Dp = 16.dp
