package fr.forumhfr.redface2.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkIntent
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkSheet
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkState
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkViewModel

/** Composed outside NavDisplay so a modo.php tap preserves the active destination and its owner. */
@Composable
internal fun ModerationAlertLinkHost(
    viewModel: ModerationAlertLinkViewModel,
    topicTitles: Map<TopicTitleKey, String>,
    onOpenRoute: (ParsedDeepLink) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModerationAlertLinkSheet(
        state = state,
        onIntent = viewModel::onIntent,
        topicTitle = state.target?.let { topicTitles[TopicTitleKey(it.cat, it.post)] },
    )
    LaunchedEffect(state) {
        val navigation = state as? ModerationAlertLinkState.NavigateToPost
        if (navigation != null && viewModel.state.value == navigation) {
            // Consume synchronously with navigation: rotation cannot replay this transition.
            viewModel.onIntent(ModerationAlertLinkIntent.Dismiss)
            onOpenRoute(navigation.toParsedDeepLink())
        }
    }
}

internal fun ModerationAlertLinkState.NavigateToPost.toParsedDeepLink(): ParsedDeepLink = ParsedDeepLink(
    destination = TopLevelDestination.Flags,
    route = TopicRoute(
        cat = target.cat,
        post = target.post,
        page = target.page,
        scrollTo = target.numreponse,
        resolveScrollToPage = target.page == 1,
        moderationAlertFor = target.numreponse.takeIf { withAlertSheet },
    ),
)
