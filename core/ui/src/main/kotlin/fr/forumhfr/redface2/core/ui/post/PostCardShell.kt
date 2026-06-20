package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
 *  - [header] (mandatory) — the poster identity. The topic wraps it in a tinted [PostIdentityBand]
 *    (full-width identity strip, anchor tint for #104); the MP passes a plain [PostIdentityHeader].
 *    The shell does NOT draw the band itself: a band is a topic affordance, and baking it here would
 *    force the band-less MP into a tinted-strip model it does not want.
 *  - [badges] (optional) — citation/multi-quote pills on the topic (#239/#436); `null` on the MP.
 *  - [body] (mandatory) — the rendered post (the call-site supplies its own [PostRenderer], choosing
 *    `selectable` itself — selection is a topic affordance #281, off for the MP, so it is NOT decided
 *    here).
 *  - [footer] (optional) — the actions row (Citer/Modifier/multi-quote) on the topic; `null` on the MP.
 *
 * [border] is the topic's multi-quote outline (#436), `null` for the MP. Body/header padding is the
 * slot's own job (the densities differ: the topic reads its gutters from the display-metrics preset,
 * the MP uses a fixed 16.dp) so the shell adds no padding of its own — it is purely the vertical
 * stack inside a `Card`. `selectable`/highlight tinting are deliberately NOT handled here.
 */
@Composable
@Suppress("LongParameterList") // Slot shell: 2 mandatory slots + modifier/border + 2 optional slots.
fun PostCardShell(
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    badges: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            header()
            badges?.invoke()
            body()
            footer?.invoke()
        }
    }
}

/**
 * #351/#104 — a tinted identity strip: a full-width [Surface] across the top of the card that hosts
 * the [content] (a [PostIdentityHeader]). Extracted as its own primitive (per the Codex framing)
 * instead of a flag on [PostCardShell], so the band-less private-message card never inherits a strip
 * it does not use.
 *
 * The tint is the call-site's decision, not `:core:ui`'s: [containerColor] is passed in (the topic
 * supplies `tertiaryContainer` for the scroll-anchor post #104 — quote link / deep link / last-read
 * landing — and `secondaryContainer` otherwise; that semantics lives in the feature, not here). The
 * `Surface` derives `LocalContentColor` from [containerColor] for the header's pseudo, and the
 * enclosing `Card` clips the strip to its rounded corners. The strip's inner padding is the call-site's
 * job (the topic reads it from its density preset), so the band adds none.
 */
@Composable
fun PostIdentityBand(
    content: @Composable () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        content()
    }
}
