package fr.forumhfr.redface2.core.domain.preferences

/**
 * Cell delimiter preference for the smiley picker (#989).
 *
 * - [NONE] (default) keeps the current picker appearance: thumbnails are shown without any cell
 *   delimiter.
 * - [OUTLINE] draws a rounded hairline around each cell so the heterogeneous smiley corpus still
 *   reads as distinct tap targets.
 * - [SEPARATORS] draws continuous table-like separators between cells for users who prefer a
 *   stronger grid cue.
 *
 * This is only a selection aid: it does not change thumbnail size, crop, or the final BBCode
 * rendering. A preset that enlarged small smileys was rejected because it made the picker misleading
 * about the final post rendering (#1022), so the preference lives at the decoration layer only.
 *
 * Pure domain enum (no Android / Compose), persisted by [name] and read defensively by the DataStore
 * repository so an unknown stored value degrades to [NONE].
 */
enum class SmileyPickerDecoration {
    NONE,
    OUTLINE,
    SEPARATORS,
}
