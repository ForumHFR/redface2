package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.model.FlagType

/**
 * Drapeau bucket palette. Material-toned colors that keep the HFR flag HUE family (cyan / red /
 * yellow). The pure gif fills (`#00FFFF` / `#FF0000` / `#F0F83F`) read garish on screen, so we use
 * the matching Material tones — Cyan 500, Red 700, Yellow 600 — which keep the bucket instantly
 * recognisable without the neon :
 *
 * - [FlagType.CYAN] → cyan, sujets participés
 * - [FlagType.RED] → rouge, lus uniquement
 * - [FlagType.FAVORITE] → jaune, favoris
 *
 * #690 — [FAVORITE] was Material Lime 500 (`#CDDC39`) but on screen it read GREEN, not yellow, so it
 * moved to Material Yellow 600 (`#FDD835`) : unmistakably yellow without the amber drift (`#F9A825`)
 * that loses the HFR favourite identity. Cyan/Red stay in their Material tones.
 *
 * Kept distinct from the Material 3 `error` / `tertiary` roles on purpose : mapping
 * the buckets to roles would either introduce ambiguity (red flag ↔ error state) or
 * lose the third axis (no neutral M3 role for a yellow favorite). The values are the
 * same in light, dark and AMOLED schemes — drapeau identity is a content marker, not
 * a surface affordance, so it stays anchored to the legacy hue regardless of theme.
 */
object FlagPalette {

    val Cyan: Color = Color(0xFF00BCD4)
    val Red: Color = Color(0xFFD32F2F)
    val Favorite: Color = Color(0xFFFDD835)

    /**
     * #603 — the « DT » (MultiMP) bucket marker, in a Material-toned fuchsia (`#D500F9`, Material
     * Purple A400) that completes the cyan/red/favori set without the neon of a pure magenta. DT is
     * NOT a [FlagType] (it is an opt-in tab fed by MPStorage #6), so this color is read directly by
     * the app-bar tab indicator / picker, never via [colorFor].
     */
    val Dt: Color = Color(0xFFD500F9)

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
