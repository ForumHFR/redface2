package fr.forumhfr.redface2.core.ui.icon

import androidx.annotation.DrawableRes
import fr.forumhfr.redface2.core.ui.R

/**
 * Maps an HFR forum category id to its Material Symbols drawable (#603, ADR-017, spike 4). The set is
 * the 19 public categories captured from the REST catalogue; any unknown id (e.g. Blabla `24`, or a
 * future category) falls back to [R.drawable.ic_ms_forum].
 *
 * Pure (returns a `@DrawableRes` id), so it is unit-testable and callable from `remember {}` blocks.
 * Icons are local vector drawables tinted at render time (ADR-015 / `RedfaceVectorIcon`) — Material
 * Icons imports are banned by detekt.
 */
@DrawableRes
fun categoryIcon(catId: Int): Int = CATEGORY_ICONS[catId] ?: R.drawable.ic_ms_forum

// catId → Material Symbol, keyed by the REST catalogue ids. A plain map (not a `when`) keeps the
// lookup at complexity 1. Unknown ids (Blabla 24, moderation 0, future cats) fall back to forum.
private val CATEGORY_ICONS: Map<Int, Int> = mapOf(
    1 to R.drawable.ic_ms_memory, // Hardware
    16 to R.drawable.ic_ms_keyboard, // Hardware - Périphériques
    15 to R.drawable.ic_ms_laptop_chromebook, // Ordinateurs portables
    2 to R.drawable.ic_ms_ac_unit, // Overclocking, Cooling & Modding
    30 to R.drawable.ic_ms_build, // Electronique, domotique, DIY
    23 to R.drawable.ic_ms_smartphone, // Technologies Mobiles
    25 to R.drawable.ic_ms_devices, // Apple
    3 to R.drawable.ic_ms_headphones, // Video & Son
    14 to R.drawable.ic_ms_photo_camera, // Photo numérique
    5 to R.drawable.ic_ms_sports_esports, // Jeux Video
    4 to R.drawable.ic_ms_desktop_windows, // Windows & Software
    22 to R.drawable.ic_ms_wifi, // Réseaux grand public / SoHo
    21 to R.drawable.ic_ms_lan, // Systèmes & Réseaux Pro
    11 to R.drawable.ic_ms_terminal, // Linux et OS Alternatifs
    10 to R.drawable.ic_ms_code, // Programmation
    12 to R.drawable.ic_ms_palette, // Graphisme
    6 to R.drawable.ic_ms_shopping_cart, // Achats & Ventes
    8 to R.drawable.ic_ms_work, // Emploi & Etudes
    13 to R.drawable.ic_ms_forum, // Discussions
)
