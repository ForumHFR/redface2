package fr.forumhfr.redface2.core.domain.preferences

/**
 * Visual style of the left-hand marker of a Drapeaux list row (#603, ADR-017). The marker encodes
 * the flag color (cyan / red / favori) and the read state; only its SHAPE varies:
 *
 * - [STRIPE] — a thin vertical color bar at the left edge (the **default**: the soberest option,
 *   frees the most width for the title).
 * - [PASTILLE] — a tonal circle carrying the category icon.
 * - [DOT] — a minimal colored dot (the legacy `FlagDot`).
 *
 * Pure domain enum (no Android / Compose), so it can be persisted in a preference and consumed by a
 * pure mapper ([fr.forumhfr.redface2.feature.flags] `toFlagRowUiModel`) without dragging UI types
 * into the model layer. The color itself is resolved at render time from the row's effective flag
 * color (`FlagPalette`), not by this enum.
 */
enum class MarkerStyle {
    STRIPE,
    PASTILLE,
    DOT,
}
