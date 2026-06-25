package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.model.FlagType

/**
 * Drapeau bucket palette. The colors are the EXACT fill values of the legacy HFR flag gifs
 * (`flag1.gif` cyan `#00FFFF` / `flag0.gif` red `#FF0000` / `favoris.gif` yellow `#F0F83F`, relevés
 * sur les gifs réels), so a user reading the forum on the web and on Redface 2 sees the same colors,
 * not just the same hue mapping :
 *
 * - [FlagType.CYAN] → cyan, sujets participés
 * - [FlagType.RED] → rouge, lus uniquement
 * - [FlagType.FAVORITE] → jaune, favoris
 *
 * Kept distinct from the Material 3 `error` / `tertiary` roles on purpose : mapping
 * the buckets to roles would either introduce ambiguity (red flag ↔ error state) or
 * lose the third axis (no neutral M3 role for a yellow favorite). The values are the
 * same in light, dark and AMOLED schemes — drapeau identity is a content marker, not
 * a surface affordance, so it stays anchored to the legacy hue regardless of theme.
 */
object FlagPalette {

    val Cyan: Color = Color(0xFF00FFFF)
    val Red: Color = Color(0xFFFF0000)
    val Favorite: Color = Color(0xFFF0F83F)

    /**
     * #603 — the « DT » (MultiMP) bucket marker. Fuchsia completes the pure cyan/red trio with the
     * CGA/EGA magenta primary (`#FF00FF`), assorti aux fills purs des gifs HFR. DT is NOT a [FlagType]
     * (it is an opt-in tab fed by MPStorage #6), so this color is read directly by the app-bar tab
     * indicator / picker, never via [colorFor].
     */
    val Dt: Color = Color(0xFFFF00FF)

    /**
     * Resolves the bucket color for a non-null [FlagType]. Plain Kotlin function — not
     * `@Composable` — so it can be called from `remember { ... }` / `derivedStateOf { ... }`
     * blocks (whose lambdas reject composable calls) as well as from a regular
     * `@Composable` body. The returned [Color] is a constant; theme branching, if ever
     * needed, would introduce a parallel `@Composable` accessor.
     */
    fun colorFor(type: FlagType): Color = when (type) {
        FlagType.CYAN -> Cyan
        FlagType.RED -> Red
        FlagType.FAVORITE -> Favorite
    }
}
