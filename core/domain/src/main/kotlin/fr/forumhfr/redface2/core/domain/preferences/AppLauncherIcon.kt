package fr.forumhfr.redface2.core.domain.preferences

/**
 * Launcher icon selected by the user (#326).
 *
 * Values are persisted by [name]. [CLASSIC] is the default and maps to the historical white
 * launcher icon; the other values only replace its adaptive-icon background.
 */
enum class AppLauncherIcon {
    CLASSIC,
    DARK,
    ROSE,
    RED,
}
