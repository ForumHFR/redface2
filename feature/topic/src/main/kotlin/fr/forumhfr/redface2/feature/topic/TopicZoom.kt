package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import fr.forumhfr.redface2.core.ui.zoom.PinchZoomState
import fr.forumhfr.redface2.core.ui.zoom.pinchZoom
import fr.forumhfr.redface2.core.ui.zoom.pinchZoomTransform
import fr.forumhfr.redface2.core.ui.zoom.rememberPinchZoomState
import kotlinx.coroutines.CoroutineScope

/**
 * #946 — feature-local test seam retained deliberately: production has no consumer and this
 * topic-named local must not become part of the shared zoom API.
 */
internal val LocalTopicZoomed = compositionLocalOf { false }

/**
 * Compatibility names keep the topic characterization suites byte-for-byte unchanged while the
 * single implementation now lives in `:core:ui`. No zoom state or calculation is duplicated here.
 */
internal typealias TopicZoomState = PinchZoomState

@Composable
internal fun rememberTopicZoomState(pageKey: Any, animationScope: CoroutineScope): TopicZoomState =
    rememberPinchZoomState(pageKey, animationScope)

internal fun Modifier.topicMagnifier(
    state: TopicZoomState,
    listState: LazyListState,
): Modifier = pinchZoom(state, listState)

internal fun Modifier.topicZoomTransform(state: TopicZoomState): Modifier =
    pinchZoomTransform(state)
