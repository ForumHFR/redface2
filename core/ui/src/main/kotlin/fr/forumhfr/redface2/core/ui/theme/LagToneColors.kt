package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.model.LagTone

/** Container + content pair of the « pages à lire » pill for one [LagTone] (#814). */
@Immutable
data class LagToneColors(
    val container: Color,
    val content: Color,
)

/**
 * #814 — colours of the « pages à lire » pill for a [LagTone], read from the ACTIVE Material scheme so
 * light / dark / AMOLED / accent « Rouge REDFACE1 » / dynamic colour all follow without a parallel
 * palette. Only CANONICAL Material 3 pairs are used (`x` / `onX`), so the ≥ 4.5:1 text contrast holds
 * by construction on every scheme, including wallpaper-derived ones :
 *
 * - [LagTone.LOW] → `surfaceVariant` / `onSurfaceVariant` : the neutral tonal chip, barely lifted off
 *   the row surface — « peu de retard, discret ». (`surfaceContainerHighest` was rejected : on AMOLED it
 *   is `#1B1616` over pure black, i.e. invisible.)
 * - [LagTone.MEDIUM] → `tertiaryContainer` / `onTertiaryContainer` : the slate tonal accent shared by
 *   every palette (#883), the only container role that stays distinguishable from BOTH the neutral
 *   [LagTone.LOW] and the red [LagTone.HIGH] in every scheme (`primaryContainer` and
 *   `secondaryContainer` collapse onto `errorContainer`'s pale rose in light — and onto each other
 *   under the « Rouge REDFACE1 » accent).
 * - [LagTone.HIGH] → `error` / `onError` : the SOLID error pair, not the tonal `errorContainer` — in
 *   light `errorContainer` (`#FFDAD6`) is almost the same pale rose as `surfaceVariant` (`#F4DDDC`), so
 *   « alerte » would have been indistinguishable from « discret ». Solid red reads as an alert on every
 *   scheme.
 *
 * Deliberately NOT tied to [FlagPalette] : the whole point of #814 is that the pill no longer follows
 * the flag colour. The pure overload is the unit-tested contract (contrast + distinctness across the
 * five static schemes) ; this composable just feeds it the current scheme.
 */
@Composable
fun lagToneColors(tone: LagTone): LagToneColors = lagToneColors(tone, MaterialTheme.colorScheme)

/** Pure tone → roles mapping, extracted for exhaustive JVM coverage against the static schemes. */
internal fun lagToneColors(tone: LagTone, scheme: ColorScheme): LagToneColors = when (tone) {
    LagTone.LOW -> LagToneColors(container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
    LagTone.MEDIUM -> LagToneColors(container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
    LagTone.HIGH -> LagToneColors(container = scheme.error, content = scheme.onError)
}
