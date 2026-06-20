package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar

/**
 * #351 — the poster-identity line shared by the topic post card and the private-message thread card:
 * an avatar, an author pseudo and a date, optionally with a contextual-menu trigger and an extra
 * sub-line. The neutral half of the identity; the tinted strip behind it is [PostIdentityBand] (a
 * separate primitive so the band-less MP can use this header on its own).
 *
 * Layout: `Row[ avatar, Column(weight 1f){ pseudo ; date + subline }, trailing ]`, centred so the
 * avatar reads against the name+date block as one tidy unit. Slots, so each feature supplies its own
 * labels/icons without `:core:ui` reaching a feature string or a material-icon:
 *  - [pseudo] (optional) — overrides the default pseudo text; the topic passes its gold-sheen
 *    `CreatorPseudoText` (#221). When `null`, a plain ellipsised [Text] of [author] is drawn. Note:
 *    [onAuthorClick] is applied to that fallback text only — a supplied [pseudo] owns its own
 *    interaction, the header does not wrap it.
 *  - [subline] (optional) — extra line under the date (the topic's `· édité` marker / nothing on MP).
 *  - [trailing] (optional) — the `⋯` per-post menu glyph (topic); `null` on the MP.
 *
 * Clicks: [RedfaceUserAvatar] carries no `onClick` of its own, so the avatar tap is a
 * `Modifier.clickable` this header wraps around it (with `role = Role.Button` and a
 * `minimumInteractiveComponentSize()` so the 40.dp avatar still meets the Material 48.dp target). The
 * pseudo fallback gets a clickable WITHOUT the min-size box (it would inflate the line and float the
 * text — same trade-off the topic card made: the avatar beside it is the 48.dp-compliant target for
 * the same action). On-click labels come from the call-site ([onAvatarClickLabel]/[onAuthorClickLabel])
 * since the labels are feature strings. [avatarContentDescription] overrides the avatar's TalkBack
 * announcement for a non-personal avatar (e.g. a multi-recipient MP labelled « Interlocuteurs
 * multiples »).
 */
@Composable
@Suppress("LongParameterList") // Shared identity slot: avatar/pseudo/date data + 2 clicks + 3 slots.
fun PostIdentityHeader(
    author: String,
    avatarUrl: String?,
    dateText: String,
    modifier: Modifier = Modifier,
    onAvatarClick: (() -> Unit)? = null,
    onAvatarClickLabel: String? = null,
    onAuthorClick: (() -> Unit)? = null,
    onAuthorClickLabel: String? = null,
    avatarContentDescription: String? = null,
    pseudo: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subline: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarModifier = if (onAvatarClick != null) {
            Modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    onClick = onAvatarClick,
                    role = Role.Button,
                    onClickLabel = onAvatarClickLabel,
                )
        } else {
            Modifier
        }
        RedfaceUserAvatar(
            avatarUrl = avatarUrl,
            author = author,
            modifier = avatarModifier,
            contentDescriptionOverride = avatarContentDescription,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (pseudo != null) {
                pseudo()
            } else {
                val pseudoModifier = if (onAuthorClick != null) {
                    Modifier.clickable(
                        onClick = onAuthorClick,
                        role = Role.Button,
                        onClickLabel = onAuthorClickLabel,
                    )
                } else {
                    Modifier
                }
                Text(
                    text = author,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = pseudoModifier,
                )
            }
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subline?.invoke()
        }
        trailing?.invoke()
    }
}
