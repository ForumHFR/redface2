package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.pagesToRead
import fr.forumhfr.redface2.core.ui.icon.categoryIcon
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics

/**
 * #603 — GLOBAL « single-line topic titles » preference, surfaced as a CompositionLocal so the leaf
 * [ForumListRow] reads it WITHOUT threading the flag through every list composable. Default 2 (the
 * historical 2-line wrap); FlagsRoute provides 1 when the user enables single-line titles. Other
 * [ForumListRow] consumers (forum / search / DT) keep the default unless they provide their own.
 */
val LocalForumRowTitleMaxLines = compositionLocalOf { 2 }

/**
 * #690 — GLOBAL « marker outline » preference, surfaced as a CompositionLocal so the leaf [FlagMarker]
 * reads it WITHOUT threading the flag through every list composable (same pattern as
 * [LocalForumRowTitleMaxLines]). Default `false` (no border); FlagsRoute provides `true` when the user
 * enables the thin outline. Other [FlagMarker] consumers keep the default unless they provide their own.
 */
val LocalFlagMarkerBorder = compositionLocalOf { false }

/**
 * Renders one row of the user's drapeaux list.
 *
 * Visual hierarchy mirrors what HFR users have spent ~20 years training their eyes on:
 * a colored dot on the left for the flag type (cyan / red / yellow), the topic title
 * in the dominant slot, and a [metadata] footer line. When [Flag.hasUnread] is true,
 * the title is rendered in [FontWeight.SemiBold] so the row visibly pops vs read
 * entries (which the favoris view exposes too).
 *
 * The footer string is passed in pre-formatted from the caller (`:feature:flags`)
 * because `:core:ui` has no localized resources of its own — keeping the i18n boundary
 * clean per the convention recorded in `docs/guides/contributing.md`.
 *
 * [FlagMetadata.end] is an optional end-aligned segment of the footer line (#325
 * follow-up: the last-reply timestamp). It is NEVER truncated — the start segment takes
 * the remaining width and ellipsises instead, so on narrow screens the date survives and
 * the author/pagination clip first (dogfooding feedback on v102: the timestamp, placed
 * last in a single string, was the part being cut off).
 *
 * Note (#99 → #457): the « Retirer le drapeau » affordance went from an inline trailing button
 * to a `SwipeToDismissBox` (#99), then to a **long-press** ([longPress], #457) — the horizontal
 * swipe now changes the flag tab, so a row-level horizontal gesture would steal it. The
 * never-consumed `trailingAction` slot was dropped in the same change (detekt parameter budget).
 *
 * [longPress] is optional so the other consumers of this row keep the plain tap behaviour;
 * when null the row uses [clickable] unchanged (no long-press semantics advertised at all).
 */
@Composable
@Suppress("LongParameterList") // Flag row binding: flag + metadata + tap + modifier + long-press + marker style.
fun FlagItem(
    flag: Flag,
    metadata: FlagMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    longPress: FlagItemLongPress? = null,
    markerStyle: MarkerStyle = MarkerStyle.STRIPE,
    subcatName: String? = null,
) {
    // FlagItem is a thin `Flag`-typed binding over the shared [ForumListRow]. #603 refonte: the leading
    // slot is now the configurable [FlagMarker] (default barre de couleur, ADR-017) and the trailing
    // slot a « pages à lire » pill when the topic is unread with pages left. DT (and any future forum
    // list) keeps rendering through the SAME row primitive — change the row once, every list follows.
    // pagesToRead comes from the single source of truth in :core:model (shared with the VM mapper) —
    // no per-layer recomputation that could silently diverge. #814 : the pill no longer takes the
    // flag's accent — it derives its tone from the count itself (see PagesToReadPill).
    val pagesToRead = flag.pagesToRead()
    ForumListRow(
        title = flag.title,
        metadata = metadata.withSubcategory(subcatName),
        onClick = onClick,
        modifier = modifier,
        emphasized = flag.hasUnread,
        longPress = longPress,
        leading = {
            FlagMarker(
                style = markerStyle,
                type = flag.type,
                isFavorite = flag.isFavorite,
                hasUnread = flag.hasUnread,
                categoryIconRes = categoryIcon(flag.cat),
            )
        },
        trailing = if (flag.hasUnread && pagesToRead > 0) {
            { PagesToReadPill(count = pagesToRead) }
        } else {
            null
        },
    )
}

private fun FlagMetadata.withSubcategory(subcatName: String?): FlagMetadata {
    val cleanSubcat = subcatName?.takeIf { it.isNotBlank() } ?: return this
    val cleanStart = start.takeIf { it.isNotBlank() }
    return copy(start = listOfNotNull(cleanStart, cleanSubcat).joinToString(" · "))
}

/**
 * The single source of truth for a forum-list row (drapeaux Cyan/Red/Favorite, DT MultiMP, and any
 * future list). It owns the visual contract — leading slot + a two-line stack (title + shared
 * [TopicMetadataLine]) — the 16 dp horizontal / density-driven vertical rhythm (#287), the tap /
 * long-press interaction and the `surface` colours. It is deliberately NOT coupled to [Flag] : a row
 * is `title` + [metadata] + an optional [leading] composable, so non-drapeau lists (DT) reuse it
 * without smuggling a fake [Flag] through.
 *
 * @param emphasized renders the title in [FontWeight.SemiBold] (unread state across every list).
 * @param longPress optional long-press affordance (#457) ; `null` keeps a plain tap with no
 *   long-press semantics advertised.
 * @param leading optional leading slot (the bucket dot for drapeaux, the inbox dot for DT).
 * @param contentDescription when non-null, REPLACES the row's announced text via
 *   [clearAndSetSemantics] on the content (the title + [metadata] texts are folded into this single
 *   description) — for rows like DT whose visible text is terse but whose state (lu/non-lu,
 *   interlocuteurs, reprise) must be spoken in full. The tap/long-press action stays announced (it
 *   lives on the row's own clickable, outside the cleared content subtree). `null` keeps the default
 *   (title + metadata read as-is), which the drapeau rows rely on alongside their removal action.
 */
// A Compose row component legitimately exposes many slots; bundling the @Composable [leading] slot
// and the [onClick] lambda into a data class would be anti-idiomatic and hurt readability.
@Suppress("LongParameterList")
@Composable
fun ForumListRow(
    title: String,
    metadata: FlagMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    longPress: FlagItemLongPress? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val rowInteraction = if (longPress != null) {
        Modifier.combinedClickable(
            onLongClick = longPress.onLongPress,
            onLongClickLabel = longPress.label,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    // #287 — listing-row vertical rhythm from the density preset (Comfort = 10 dp, the lot A value).
    val m = LocalDisplayMetrics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowInteraction)
            // #603 — IntrinsicSize.Min lets a fillMaxHeight leading marker (the STRIPE « barre de
            // couleur ») span the row's content height (1- or 2-line title) instead of a fixed 32 dp
            // that fell short on 2-line titles. Per-row intrinsic pass, negligible cost.
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = m.listRowVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (contentDescription != null) {
                        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                // #603 — GLOBAL single-line-title pref via CompositionLocal (default = 2-line wrap).
                maxLines = LocalForumRowTitleMaxLines.current,
                overflow = TextOverflow.Ellipsis,
            )
            // #376 — shared two-segment metadata line (start truncatable + end pinned right),
            // common to the drapeaux / catégorie / recherche / DT lists.
            TopicMetadataLine(
                metadata = metadata,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // #603 — optional trailing slot (the drapeaux « pages à lire » pill); pinned right after the
        // weighted title/metadata column. Null for the other lists (DT), which keep the 2-slot layout.
        trailing?.invoke()
    }
}

/**
 * Bottom divider mirroring the visual rhythm of HFR topic listings. Exposed separately
 * from [FlagItem] so callers can choose whether to draw it (e.g. last item of a page).
 */
/**
 * Optional long-press affordance of a [FlagItem] row (#457). [label] doubles as the
 * accessibility `onLongClickLabel` announced for the long-press action.
 */
data class FlagItemLongPress(
    val label: String,
    val onLongPress: () -> Unit,
)

@Composable
fun FlagItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
