package fr.forumhfr.redface2.core.domain.preferences

/**
 * Resolved Drapeaux view layout for one tab (#309). Bundles the two persisted toggles the Flags
 * screen consumes:
 *
 * - [groupByCategory] — group flags by forum category (sticky bands) vs. the legacy flat list.
 * - [hideReadCategories] — trim categories without an unread flag in the grouped view (no effect
 *   in the flat view).
 *
 * "Resolved" is load-bearing. The app exposes a single GLOBAL pair of toggles plus an optional
 * PER-TAB override ([UserPreferencesRepository.observeFlagsPerTabOverride]). When the override is
 * off, every tab sees the global values; when it is on, each tab sees its own stored value and
 * falls back to the global value for any toggle that tab has never customised.
 * [UserPreferencesRepository.observeFlagsViewSettings] performs that resolution so the ViewModel
 * never has to.
 *
 * Defaults match the global DataStore defaults (#179): grouped on, hide-read off.
 */
data class FlagsViewSettings(
    val groupByCategory: Boolean = true,
    val hideReadCategories: Boolean = false,
)
