package fr.forumhfr.redface2.core.domain.preferences

/**
 * Visual style of the active-type glyph in the Drapeaux top-bar left container (#603/#665). The glyph
 * sits in the « flag zone » (the button that opens the quick menu); only its shape varies:
 *
 * - [Flag] — the section's coloured flag icon (the **default**: the « drapal » reprise, XaTriX).
 * - [Dot] — a minimal coloured pastille (the legacy dot), for users who prefer the soberest cue.
 *
 * Pure domain enum (no Android / Compose), so it can be persisted in a preference and read by the top
 * bar without dragging UI types into the model layer. GLOBAL (one value for every tab), like
 * [MarkerStyle] / [PlusLusIndicatorStyle]; the colour is resolved at render time from the active type.
 */
enum class FlagGlyphStyle {
    Flag,
    Dot,
}
