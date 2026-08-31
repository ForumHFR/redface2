package fr.forumhfr.redface2.core.domain.preferences

/**
 * Synchronous mirror of the persisted « show bottom-nav labels » preference (#666), readable on
 * the cold-start path BEFORE DataStore's first emission — same rationale as [ThemeBootstrapStore]
 * (#386). Without it, a user who hid the labels saw them flash on every cold start: the shell
 * seeds `navBarLabels` from the hard-coded `true` default until DataStore hydrates, so the
 * `NavigationSuiteScaffold` rendered the labels for the first frames before the stored `false`
 * landed and dropped them (#1138).
 *
 * Written by the preferences repository on every change (and backfilled from the observed DataStore
 * value, so users who hid the labels BEFORE the mirror existed converge on first launch); read as
 * the StateFlow seed at ViewModel construction. DataStore stays the source of truth — if the two
 * ever diverge (partial restore, cleared mirror), the DataStore value wins as soon as it is hydrated.
 *
 * The default is `true` (labels shown, the historical M3 behaviour): an empty mirror reads as `true`,
 * matching the DataStore read default, so a fresh install and a user who never toggled behave alike.
 */
interface NavBarLabelsBootstrapStore {
    fun read(): Boolean
    fun write(enabled: Boolean)
}
