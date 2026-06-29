package fr.forumhfr.redface2.core.domain.preferences

/**
 * Visual style of the « +lus » cue in the Drapeaux top-bar left container (#661/#603, ADR-017). When
 * the active tab (Cyan / DT) is showing read items, the container signals it; only WHERE and the SHAPE
 * of the cue varies:
 *
 * - [Ring] — a coloured ring is drawn AROUND the active-type glyph in zone 1 (the **default**, #603/A
 *   chosen by XaTriX: the cue lives on the flag/dot itself, reprise of the original « anneau » idea).
 * - [Eye] — an eye glyph in a tinted capsule next to the type name in zone 2 (the legacy, most
 *   explicit option).
 *
 * Pure domain enum (no Android / Compose), so it can be persisted in a preference and read by the
 * top bar without dragging UI types into the model layer. GLOBAL (one value for every tab), like
 * [MarkerStyle]; the colour is resolved at render time from the active tab's flag colour.
 */
enum class PlusLusIndicatorStyle {
    Eye,
    Ring,
}
