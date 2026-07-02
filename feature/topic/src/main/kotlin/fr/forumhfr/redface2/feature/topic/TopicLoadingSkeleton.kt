package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.loading.SkeletonBox

/**
 * #604 — loading state of a topic page (mockup « Chargement · A Skeleton », arbitré sur le fil DEV) :
 * a centered Material loader with a plain-language label, then ghost post cards shimmering below, so
 * the screen reads as « la page arrive » instead of a bare top-left spinner. The REAL topic title is
 * already in the persistent top bar (`TopicRequest.titleHint` contract) and the page counter shows
 * « Chargement… » until the response is parsed (#622 — never a stale total).
 *
 * The skeleton blocks are decorative (no contentDescription — the visible label carries the state);
 * shimmer motion is gated system-wide by [SkeletonBox]'s reduce-motion default. Card count is fixed:
 * enough to fill a phone viewport without composing off-screen work.
 */
@Composable
internal fun TopicLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.topic_loading_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        repeat(SKELETON_CARD_COUNT) {
            SkeletonPostCard()
        }
    }
}

/** One ghost post card: identity row (round avatar + author/date lines) above body lines. */
@Composable
private fun SkeletonPostCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(modifier = Modifier.size(30.dp).clip(CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonLine(widthFraction = 0.45f, height = 10.dp)
                    SkeletonLine(widthFraction = 0.28f, height = 8.dp)
                }
            }
            SkeletonLine(widthFraction = 1f, height = 9.dp)
            SkeletonLine(widthFraction = 0.92f, height = 9.dp)
            SkeletonLine(widthFraction = 0.68f, height = 9.dp)
        }
    }
}

@Composable
private fun SkeletonLine(widthFraction: Float, height: Dp) {
    SkeletonBox(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp)),
    )
}

/** Fills a phone viewport below the loader without composing off-screen extra cards. */
private const val SKELETON_CARD_COUNT = 3
