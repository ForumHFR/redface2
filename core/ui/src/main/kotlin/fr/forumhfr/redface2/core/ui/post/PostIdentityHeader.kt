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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 *    interaction. CONTRACT (#884): the provided slot also OWNS the post heading semantics — exactly
 *    one node inside it must set `semantics { heading() }`, on the real pseudo text node (the best
 *    TalkBack target). The header deliberately adds NO heading around a supplied slot: wrapping it
 *    would DOUBLE the per-post heading for every caller that marks its own node. The flip side is
 *    that a slot which forgets the marker silently loses heading navigation — guards:
 *    `PostIdentityHeaderTest` (contract, both directions) plus one exactly-one-heading assert per
 *    production variant (`TopicPostCardFullWidthTest`, `MessageCardShellSmokeTest`).
 *  - [dateTrailing] (optional) — a marker on the SAME row as the date, to its right (the topic's
 *    and MP's data-driven `· édité`, #483/#1051); `null` keeps the date as a plain single line.
 *  - [subline] (optional) — extra line under the date; unused by the topic now, available for MP.
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
 *
 * A11y (#884) : the pseudo line is a TalkBack heading — heading navigation jumps from post to post
 * on the identity line, on the topic AND the MP. The fallback [Text] carries `heading()` on its own
 * node; a supplied [pseudo] slot OWNS its heading instead (vague 3, cf. the [pseudo] contract
 * above): exactly one node inside the slot sets `semantics { heading() }` and the header adds no
 * wrapper around it (a generic wrapper heading would DOUBLE the per-post heading, cf.
 * `TopicPostCardFullWidthTest`). No synthetic contentDescription — nothing is announced twice.
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
    avatarSpacing: Dp = DEFAULT_AVATAR_SPACING,
    lineSpacing: Dp = DEFAULT_LINE_SPACING,
    pseudo: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    dateTrailing: (@Composable () -> Unit)? = null,
    subline: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(avatarSpacing),
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
            verticalArrangement = Arrangement.spacedBy(lineSpacing),
        ) {
            if (pseudo != null) {
                // #884 a11y — a caller-supplied pseudo owns its heading semantics (the topic marks
                // its real pseudo text node); wrapping the slot here would double the heading.
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
                    // #884 a11y — heading() rides on the pseudo node itself (best TalkBack target).
                    modifier = pseudoModifier.semantics { heading() },
                )
            }
            if (dateTrailing != null) {
                // Date + an inline trailing marker on the SAME row (e.g. the topic's « · édité » #483),
                // 4.dp apart — kept on one line rather than stacked, so an edited post reads exactly
                // like the pre-shell layout. A bare [Text] is used when no trailing is supplied so the
                // common case (MP, non-edited post) stays byte-identical to a single date line.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    dateTrailing()
                }
            } else {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            subline?.invoke()
        }
        trailing?.invoke()
    }
}

/** Default avatar↔identity gap. Overridable by the call-site (#351 — density stays feature-owned). */
private val DEFAULT_AVATAR_SPACING: Dp = 12.dp

/** Default pseudo↔date gap. Overridable by the call-site (#351 — density stays feature-owned). */
private val DEFAULT_LINE_SPACING: Dp = 2.dp
