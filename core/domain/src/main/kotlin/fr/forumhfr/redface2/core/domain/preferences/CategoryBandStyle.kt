package fr.forumhfr.redface2.core.domain.preferences

/**
 * Visual treatment of the sticky CATEGORY BAND in the grouped Drapeaux list (#603). Only the band's
 * styling varies; its content (category icon, name, open-chevron) and its tap-to-open behaviour
 * (#414) are identical across styles. The band only appears in the grouped view — it has no effect
 * on the flat list.
 *
 * - [MINIMAL] — the **default** : an opaque subhead with an uppercase letter-spaced name and a
 *   hairline divider below (the look shipped in 0.17.3, #654).
 * - [SOFT] — a soft tonal block (`surfaceContainer`), no divider.
 * - [ACCENT] — a left vertical accent bar + a tinted icon (the `primary` accent), opaque base.
 * - [BULLET] — the category name carried in a tonal chip (`surfaceContainerHigh`), opaque base.
 *
 * All four render on an OPAQUE base background : the band is a `stickyHeader`, so a transparent
 * background would let the scrolling rows bleed through. The two originally-transparent mockups
 * ([ACCENT], [BULLET]) are therefore opacified for this sticky context.
 *
 * Pure domain enum (no Android / Compose), so it can be persisted in a preference and read back
 * defensively (an unknown stored value degrades to [MINIMAL]). The colours themselves are resolved
 * at render time from the M3 colour scheme, not by this enum.
 */
enum class CategoryBandStyle {
    MINIMAL,
    SOFT,
    ACCENT,
    BULLET,
}
