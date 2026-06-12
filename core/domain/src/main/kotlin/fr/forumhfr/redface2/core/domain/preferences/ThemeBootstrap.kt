package fr.forumhfr.redface2.core.domain.preferences

/**
 * Last persisted theme selection, readable SYNCHRONOUSLY during the cold-start window (#386).
 *
 * DataStore's first emission can take over a second on a cold start ; until it lands the app
 * used to render with the [ThemeMode.SYSTEM] default, flashing the OS theme at any user who
 * forced the opposite one (light flash on a forced-dark app under a light OS). The window
 * background and the first composition both need the effective theme immediately, hence this
 * mirror. Defaults (SYSTEM / amoled off) apply until the user changes the theme once.
 */
data class ThemeBootstrap(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledEnabled: Boolean = false,
)

/**
 * Synchronous mirror of the persisted theme preferences (#386). Written by the preferences
 * repository on every theme change (and backfilled from the observed DataStore value, so
 * users who picked their theme before the mirror existed converge on first launch) ; read
 * on the cold-start path BEFORE the first DataStore emission. DataStore stays the source of
 * truth — if the two ever diverge (partial restore, cleared mirror), the DataStore value
 * wins as soon as it is hydrated.
 *
 * Writes are PER KEY on purpose : theme mode and AMOLED are persisted from independent
 * coroutines, and a read-modify-write of the whole pair could lose the other field.
 */
interface ThemeBootstrapStore {
    fun read(): ThemeBootstrap
    fun writeThemeMode(mode: ThemeMode)
    fun writeAmoledEnabled(enabled: Boolean)
}
