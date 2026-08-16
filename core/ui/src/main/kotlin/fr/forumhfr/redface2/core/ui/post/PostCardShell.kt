package fr.forumhfr.redface2.core.ui.post

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics

/**
 * #351 — the neutral anatomy shared by the topic post card and the private-message thread card.
 *
 * Both screens render the *same skeleton* — an identity header, an optional badge strip, the post
 * body, and an optional footer of actions — but with their own labels, icons and colours. This shell
 * is the **structure** factored out (ADR-013: share the components, not the screens); every feature
 * fills its [header]/[badges]/[footer]/[body] slots with its own composables, so `:core:ui` never has
 * to reach a feature string (`topic_*`/`messages_*`, which live behind each feature's own `R`) nor a
 * material-icon (detekt `ForbiddenImport` blocks `androidx.compose.material.*`).
 *
 * A `Card` over [MaterialTheme.colorScheme.surfaceContainer] (the neutral card colour both screens
 * already used) with the slots stacked top to bottom:
 *  - [header] (mandatory) — the poster identity. Both reading hosts wrap their
 *    [PostIdentityHeader] in a full-width [PostIdentityBand]: the topic owns its variable anchor tint
 *    (#104), while the MP owns a fixed identity tint. The shell does NOT draw the band itself because
 *    its colour remains a feature decision.
 *  - [badges] (optional) — citation/multi-quote pills on the topic (#239/#436), or the
 *    data-driven citation-count pill on a private message (#1051).
 *  - [body] (mandatory) — the rendered post (the call-site supplies its own [PostRenderer] and owns
 *    its `selectable` choice — the shell decides nothing here; since #1042 both reading surfaces
 *    mount their body through `ReadingPostCard`, whose selection is constant by construction #946).
 *  - [footer] (optional) — the actions row (Citer/Modifier/multi-quote) on the topic; `null` on the MP.
 *
 * [border] is the topic's multi-quote outline (#436), `null` for the MP. Body/header padding is the
 * slot's own job (both reading hosts read their gutters from the display-metrics preset since #1042,
 * each reinjecting them on its own slots) so the shell adds no padding of its own — it is purely the
 * vertical stack inside a `Card`. `selectable`/highlight tinting are deliberately NOT handled here.
 *
 * [flat] is the full-width mode (#884 — « posts en pleine largeur ») : the SAME `Card` (the node
 * structure never changes, so slot memoization stays positional — the #946 guarantee) rendered
 * boundary-less — [RectangleShape], transparent container, and `onSurface` content colour (pinned
 * explicitly: it is what `contentColorFor(surfaceContainer)` resolves to in the default mode, so
 * text cannot drift when the background goes transparent). A hairline `outlineVariant`
 * [HorizontalDivider] drawn STRICTLY after the slots closes the post instead of the card boundary.
 * It is suppressed in two cases:
 *  - [border] is supplied (multi-quote selection #436) — the outline closes the post on all four
 *    sides, and stacking the hairline under it would double-stroke the bottom edge;
 *  - [flatBottomEdge] is [PostCardShellFlatBottomEdge.NONE] — the OWNER of the sequence has decided
 *    this post draws no closing rule, either because the next element brings its own boundary or
 *    because the sequence ends here (#983). The shell cannot know either fact; it renders the
 *    decision.
 *
 * The default (`flat = false`) composition structure and visual/layout rendering stay unchanged
 * from the pre-#884 card; existing call-sites pass nothing. The semantics tree additionally exposes
 * diagnostic colour keys, ignored by accessibility services, for regression tests.
 *
 * [containerColorOverride] replaces only the card container for feature-owned highlights. It does
 * not change [flat]'s rectangular shape or closing hairline, and its `null` default preserves the
 * historical card and transparent full-width branches exactly.
 *
 * A11y (#884) : the card is one TalkBack traversal group in BOTH modes ([isTraversalGroup] composed
 * onto the caller's [modifier]) — M3's `Surface` only sets the deprecated `IsContainer` key, so the
 * shell owns the real one.
 */
@Composable
@Suppress("LongParameterList")
// Slot shell: 2 mandatory slots + modifier/flat/container override/border/edge + 2 optional slots.
fun PostCardShell(
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    containerColorOverride: Color? = null,
    border: BorderStroke? = null,
    badges: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    flatBottomEdge: PostCardShellFlatBottomEdge = PostCardShellFlatBottomEdge.HAIRLINE,
) {
    val effectiveContainerColor = containerColorOverride ?: if (flat) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        modifier = modifier.semantics {
            isTraversalGroup = true
            this[PostCardShellContainerColorKey] = effectiveContainerColor
        },
        border = border,
        shape = if (flat) RectangleShape else CardDefaults.shape,
        // One `cardColors` call for every colour state, rather than branching on `flat`: the
        // resolved colour is already decided above, so a single expression keeps this call site
        // readable. Changing it in place recomposes the card — it never replaces the subtree.
        colors = CardDefaults.cardColors(
            containerColor = effectiveContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            header()
            badges?.invoke()
            body()
            footer?.invoke()
            if (flat && border == null && flatBottomEdge == PostCardShellFlatBottomEdge.HAIRLINE) {
                HorizontalDivider(
                    modifier = Modifier.testTag(POST_CARD_SHELL_DIVIDER_TAG),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/**
 * #884 — tag of the hairline that closes a `flat` [PostCardShell] (dividers have no text/semantics
 * to assert on; testTag is invisible to TalkBack). Public so feature-module tests can pin its
 * ABSENCE on their default card rendering — same precedent as `BBCODE_FIELD_PINNED_LABEL_TAG`.
 */
const val POST_CARD_SHELL_DIVIDER_TAG = "PostCardShellDivider"

/**
 * Compose-test diagnostic ignored by accessibility services.
 *
 * Public only so tests in consumer modules can inspect the rendered shell; feature production code
 * must not use it as a behavioural or styling contract.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
val PostCardShellContainerColorKey = SemanticsPropertyKey<Color>("PostCardShellContainerColor")

/**
 * #351/#104 — a tinted identity strip: a full-width [Surface] across the top of the card that hosts
 * the [content] (a [PostIdentityHeader]). Extracted as its own primitive (per the Codex framing)
 * instead of a flag on [PostCardShell], so each reading host opts into the strip and owns its tint.
 *
 * The tint is the call-site's decision, not `:core:ui`'s: [containerColor] is passed in (the topic
 * supplies `tertiaryContainer` for the scroll-anchor post #104 — quote link / deep link / last-read
 * landing — and `secondaryContainer` otherwise; the MP always supplies `secondaryContainer`). That
 * semantics lives in the feature, not here. The `Surface` derives `LocalContentColor` from
 * [containerColor] for the header's pseudo, and the enclosing `Card` clips the strip to its shape
 * (rounded inset card or rectangle in full-width mode). The strip's inner padding is the call-site's
 * job, so the band adds none.
 */
@Composable
fun PostIdentityBand(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this[PostIdentityBandContainerColorKey] = containerColor },
        color = containerColor,
    ) {
        content()
    }
}

/**
 * Compose-test diagnostic ignored by accessibility services.
 *
 * Public only so tests in consumer modules can inspect the rendered band; feature production code
 * must not use it as a behavioural or styling contract.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
val PostIdentityBandContainerColorKey = SemanticsPropertyKey<Color>("PostIdentityBandContainerColor")
