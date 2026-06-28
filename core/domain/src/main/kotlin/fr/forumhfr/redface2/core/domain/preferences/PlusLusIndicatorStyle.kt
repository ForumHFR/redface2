package fr.forumhfr.redface2.core.domain.preferences

/**
 * Visual style of the « +lus » cue in the Drapeaux top-bar tab picker (#661, ADR-017). When the
 * active tab (Cyan / DT) is showing read items, the left container signals it; only the SHAPE of the
 * cue varies:
 *
 * - [Eye] — an eye glyph in a tinted capsule next to the tab name (the **default**: the most
 *   explicit, self-describing on first use).
 * - [Ring] — the section's flag dot is drawn as a hollow coloured ring instead of a filled disc
 *   (the soberest option: no extra glyph, the « drapal » itself carries the state).
 *
 * Pure domain enum (no Android / Compose), so it can be persisted in a preference and read by the
 * top bar without dragging UI types into the model layer. GLOBAL (one value for every tab), like
 * [MarkerStyle]; the colour is resolved at render time from the active tab's flag colour.
 */
enum class PlusLusIndicatorStyle {
    Eye,
    Ring,
}
