package fr.forumhfr.redface2.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkIntent
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkSheet
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkState
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkViewModel
import fr.forumhfr.redface2.feature.topic.ModerationAlertLoadingBar

/** Overlays the initial read above NavDisplay, then shows the sheet without replaying Open on rotation. */
@Composable
internal fun ModerationAlertLinkHost(
    viewModel: ModerationAlertLinkViewModel,
    topicTitles: Map<TopicTitleKey, String>,
    onOpenRoute: (ParsedDeepLink) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Snapshot even a missing title at Open, before the read or a late topic-cache update can resize the sheet.
    val topicTitle by rememberSaveable(state.target) {
        mutableStateOf(state.target?.let { topicTitles[TopicTitleKey(it.cat, it.post)] })
    }
    BackHandler(enabled = state is ModerationAlertLinkState.Loading) {
        viewModel.onIntent(ModerationAlertLinkIntent.Dismiss)
    }
    ModerationAlertLoadingBar(
        visible = (state as? ModerationAlertLinkState.Loading)?.keepSheetOpen == false,
        modifier = Modifier.statusBarsPadding(),
    )
    ModerationAlertLinkSheet(
        state = state,
        onIntent = viewModel::onIntent,
        topicTitle = topicTitle,
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
