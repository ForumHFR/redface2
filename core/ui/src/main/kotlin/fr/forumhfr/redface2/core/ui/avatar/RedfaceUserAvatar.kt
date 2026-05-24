package fr.forumhfr.redface2.core.ui.avatar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/**
 * Square (rounded-corner) avatar used in post headers and the account menu badge.
 *
 * Issues #198 / #201 explicitly call for a square / rectangular shape, **not** a circle —
 * cohérent avec la convention HFR web et la forme retenue pour le badge compte global.
 *
 * Loading + error fall back to a placeholder built from the author's first character on a
 * tinted [Surface], avoiding the need for `material-icons-extended` (the project deliberately
 * does not depend on it).
 *
 * Lives in `:core:ui` because Coil is already wired here (`coil-compose` + `coil-network-okhttp`
 * in `core/ui/build.gradle.kts`) — `:feature:topic` does not need to grow a Coil dependency to
 * use this component.
 */
@Composable
fun RedfaceUserAvatar(
    avatarUrl: String?,
    author: String,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
) {
    val shape = RoundedCornerShape(AVATAR_CORNER_RADIUS)
    val initial = author.firstOrNull()?.uppercaseChar()?.toString().orEmpty()

    if (avatarUrl.isNullOrBlank()) {
        // Standalone branch — no SubcomposeAsyncImage parent to carry a contentDescription,
        // so the placeholder itself must announce the author to TalkBack. We pass the
        // author down so AvatarPlaceholder can attach a `semantics(mergeDescendants=true)`
        // contentDescription on its Surface (#207 round-3, F2 R3).
        AvatarPlaceholder(
            initial = initial,
            modifier = modifier.size(size),
            shape = shape,
            standaloneContentDescription = stringResource(R.string.avatar_content_description, author),
        )
        return
    }

    // Round-2 review (PR #207): set `contentDescription = author` so TalkBack announces "avatar
    // de <pseudo>" instead of "image" + a separate pseudo Text below — the two were unlinked
    // in the semantic tree and produced a noisy double announcement.
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(avatarUrl)
            .build(),
        contentDescription = author,
        modifier = modifier
            .size(size)
            .clip(shape),
        contentScale = ContentScale.Crop,
        loading = {
            AvatarPlaceholder(initial = initial, modifier = Modifier.fillMaxSize(), shape = shape)
        },
        error = {
            AvatarPlaceholder(initial = initial, modifier = Modifier.fillMaxSize(), shape = shape)
        },
    )
}

@Composable
private fun AvatarPlaceholder(
    initial: String,
    modifier: Modifier,
    shape: Shape,
    // null when used as a SubcomposeAsyncImage loading/error slot — the parent already carries
    // `contentDescription = author`, so we keep the placeholder muted to avoid double
    // announcement. Non-null in standalone mode (no avatar URL) so TalkBack still reads the
    // author through the placeholder Surface (#207 round-3, F2 R3).
    standaloneContentDescription: String? = null,
) {
    val surfaceModifier = if (standaloneContentDescription != null) {
        modifier.semantics(mergeDescendants = true) {
            contentDescription = standaloneContentDescription
        }
    } else {
        modifier
    }
    Surface(
        modifier = surfaceModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                // Decorative letter — semantics are owned by the Surface (standalone) or by
                // the SubcomposeAsyncImage parent (slot mode). Clearing here avoids the double
                // announcement TalkBack would otherwise produce.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

private val DEFAULT_SIZE: Dp = 40.dp
private val AVATAR_CORNER_RADIUS: Dp = 8.dp
