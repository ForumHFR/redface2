package fr.forumhfr.redface2.core.domain.preferences

/**
 * Launcher icon selected by the user (#326).
 *
 * Values are persisted by [name]. Retired backgrounds remain readable for the 0.54.0 migration;
 * only [selectable] entries belong in the gallery.
 */
enum class AppLauncherIcon(val selectable: Boolean) {
    CLASSIC(true),
    RF1(true),
    @Deprecated("Retained only for the 0.54.0 launcher migration")
    DARK(false),
    @Deprecated("Retained only for the 0.54.0 launcher migration")
    ROSE(false),
    @Deprecated("Retained only for the 0.54.0 launcher migration")
    RED(false),
    ;

    companion object {
        val selectable: List<AppLauncherIcon> = entries.filter { it.selectable }
    }
}
