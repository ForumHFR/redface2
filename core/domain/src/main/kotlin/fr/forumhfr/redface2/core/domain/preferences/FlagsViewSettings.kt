package fr.forumhfr.redface2.core.domain.preferences

/**
 * Resolved Drapeaux view layout for one tab (#309). Bundles the two persisted toggles the Flags
 * screen consumes:
 *
 * - [groupByCategory] — group flags by forum category (sticky bands) vs. the legacy flat list.
 * - [hideReadCategories] — trim categories without an unread flag in the grouped view (no effect
 *   in the flat view).
 * - [unreadOnly] — show only topics with an unread post, in BOTH the flat and grouped views (#317).
 *
 * "Resolved" is load-bearing. The app exposes a single GLOBAL pair of layout toggles
 * ([groupByCategory] / [hideReadCategories]) plus an optional PER-TAB override
 * ([UserPreferencesRepository.observeFlagsPerTabOverride]). When the override is off, every tab
 * sees the global layout values; when it is on, each tab sees its own stored value and falls back
 * to the global value for any toggle that tab has never customised.
 * [UserPreferencesRepository.observeFlagsViewSettings] performs that resolution so the ViewModel
 * never has to.
 *
 * [unreadOnly] is different: it is ALWAYS per flag type (not subject to the override), because
 * "show only unread" has a genuinely type-specific natural default — CYAN (« Mes sujets ») defaults
 * to unread-only (the actionable subset), while RED (« Lus ») and FAVORITE default to showing every
 * topic. The repository applies that type-aware default; toggling it (bottom sheet, or the CYAN
 * re-tap shortcut) writes the per-type value.
 *
 * Data-class defaults match the global layout defaults (#179): grouped on, hide-read off. The
 * [unreadOnly] default here is `false` (the safe "show all") — the meaningful type-aware default is
 * applied at resolution time in the repository, not by this constructor.
 */
data class FlagsViewSettings(
    val groupByCategory: Boolean = true,
    val hideReadCategories: Boolean = false,
    val unreadOnly: Boolean = false,
    // #603 PR6 — left-marker shape. GLOBAL (not subject to the per-tab override): one shape for every
    // tab. Default STRIPE (ADR-017, soberest option). Carried on every resolution path.
    val markerStyle: MarkerStyle = MarkerStyle.STRIPE,
    // #603 — GLOBAL: keep topic titles on a single (ellipsised) line instead of wrapping to 2. Default
    // false = the historical 2-line wrap. Not subject to the per-tab override (like [markerStyle]).
    val singleLineTitle: Boolean = false,
    // #603 — GLOBAL: visual treatment of the grouped-view category band. Default MINIMAL (the look
    // shipped in 0.17.3). Not subject to the per-tab override (like [markerStyle]). Carried on every
    // resolution path.
    val categoryBandStyle: CategoryBandStyle = CategoryBandStyle.MINIMAL,
    // #690 — GLOBAL: draw a thin (0.5 dp) dark outline around the colored marker so the amber FAVORITE
    // (#FFB300) reads cleanly on a light background. Default false (no border). Not subject to the
    // per-tab override (like [markerStyle]). Carried on every resolution path.
    val markerBorder: Boolean = false,
)
