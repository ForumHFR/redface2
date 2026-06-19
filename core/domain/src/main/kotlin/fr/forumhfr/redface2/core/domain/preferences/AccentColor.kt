package fr.forumhfr.redface2.core.domain.preferences

/**
 * Accent (primary/secondary) colour family applied across the app (TU 2788511 — XaTriX).
 *
 * The neutral surfaces stay identical between variants on purpose: the accent only re-tints the
 * primary/secondary roles (buttons, FAB, selection, links, the post identity bands), NOT the window
 * background — so, like [DisplayDensity], this is a Compose-only theme detail that does not need the
 * synchronous cold-start mirror used by [ThemeMode] / AMOLED (those paint the window background).
 *
 * - [ROSE] (default) keeps the historical muted maroon/rose scheme (seed `#A62C2C`).
 * - [ROUGE_REDFACE1] switches to a vivid red scheme seeded from `#F44336`, the signature red of
 *   Redface 1 (`theme_primary`).
 *
 * Persisted by its [name] like [ThemeMode] / [DisplayDensity] / [ImmersiveNavBarReveal]; observed at
 * the app root and passed to `RedfaceTheme`.
 */
enum class AccentColor {
    ROSE,
    ROUGE_REDFACE1,
}
