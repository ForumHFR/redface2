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
@Suppress("LongParameterList") // Shared avatar component: each optional has a distinct call-site need.
fun RedfaceUserAvatar(
    avatarUrl: String?,
    author: String,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
    // Overrides the TalkBack announcement when the avatar does not represent a single named
    // person — e.g. a multi-recipient MP conversation, where `author` is a UI label like
    // "Interlocuteurs multiples" and "Avatar de Interlocuteurs multiples" would read wrong.
    // `null` keeps the default "Avatar de <author>" derivation.
    contentDescriptionOverride: String? = null,
    // #603/#665 — shape of the avatar. Defaults to the HFR-web rounded square used in post headers;
    // the global account badge (top-bar « PP ») passes [androidx.compose.foundation.shape.CircleShape]
    // so the profile picture reads as a round avatar (XaTriX, top-bar redesign vision).
    shape: Shape = RoundedCornerShape(AVATAR_CORNER_RADIUS),
) {
    val initial = author.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    // Same localized "Avatar de <pseudo>" string for both branches so TalkBack reads the
    // same sentence whether the avatar URL was provided or not (Codex rereview on PR #207
    // flagged that the loaded-image branch was leaking the raw pseudo into the announcement).
    val avatarContentDescription = contentDescriptionOverride
        ?: stringResource(R.string.avatar_content_description, author)

    if (avatarUrl.isNullOrBlank()) {
        // Standalone branch — no SubcomposeAsyncImage parent to carry a contentDescription,
        // so the placeholder itself must announce the author to TalkBack. We pass the same
        // localized string down so AvatarPlaceholder can attach `semantics(mergeDescendants=true)`
        // on its Surface.
        AvatarPlaceholder(
            initial = initial,
            modifier = modifier.size(size),
            shape = shape,
            standaloneContentDescription = avatarContentDescription,
        )
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(avatarUrl)
            .build(),
        contentDescription = avatarContentDescription,
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
