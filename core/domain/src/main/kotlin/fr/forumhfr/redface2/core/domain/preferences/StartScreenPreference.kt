package fr.forumhfr.redface2.core.domain.preferences

/** Which top-level tab a cold start lands on (#458). */
enum class StartScreenChoice { FLAGS, FORUM, MESSAGES }

/**
 * Start-screen selection (#458): which tab — and for the Forum tab, optionally which category —
 * the app opens on. [forumCatId] is only meaningful when [screen] is [StartScreenChoice.FORUM]:
 * a positive id pre-stacks that category's topic listing on the Forum tab, `null` opens the
 * forum root. Defaults preserve the historical behaviour (Drapeaux tab).
 */
data class StartScreenPreference(
    val screen: StartScreenChoice = StartScreenChoice.FLAGS,
    val forumCatId: Int? = null,
)

/**
 * Synchronous mirror of the persisted start-screen preference (#458), same rationale as
 * [ThemeBootstrapStore] (#386): the navigation seeds its initial tab and back stack during the
 * very first composition, long before DataStore's first emission — a hard-coded default there
 * would flash the Drapeaux tab before jumping. Written by the preferences repository on every
 * change (and backfilled from the observed DataStore value); DataStore stays the source of
 * truth. Unlike the theme mirror, [write] persists the whole pair atomically — both fields are
 * always set together from the single Settings flow.
 */
interface StartScreenBootstrapStore {
    fun read(): StartScreenPreference
    fun write(preference: StartScreenPreference)
}
