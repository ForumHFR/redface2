package fr.forumhfr.redface2.core.domain.preferences

/**
 * Reading-density preset (#287 lot B).
 *
 * - [COMFORT] (default) keeps the historical structural rhythm — the listing-row and
 *   post-body paddings shipped by the #398 structural pass, calibrated for comfortable reading.
 * - [COMPACT] tightens those same paddings so more posts / listing rows fit on screen, for
 *   users who asked for a denser feed in the beta feedback.
 *
 * The preset only drives the structural spacing exposed through `LocalDisplayMetrics`; it never
 * touches the typography (that is [FontScalePreference]) nor the system font zoom. Modelled on
 * [ThemeMode]: persisted by its [name] and observed at the app root.
 */
enum class DisplayDensity {
    COMFORT,
    COMPACT,
}
