package fr.forumhfr.redface2.core.domain.preferences

/**
 * App theme selection (#286).
 *
 * - [SYSTEM] (default) follows the OS dark-mode setting — the historical behaviour, where
 *   `RedfaceTheme` read `isSystemInDarkTheme()` directly.
 * - [LIGHT] / [DARK] force the app theme regardless of the OS, for users whose phone is in light
 *   mode but who want a dark app (and vice-versa) — the explicit bêta request behind #286.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}
