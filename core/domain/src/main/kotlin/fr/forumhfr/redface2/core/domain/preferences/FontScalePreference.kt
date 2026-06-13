package fr.forumhfr.redface2.core.domain.preferences

/**
 * Reading font-size preset (#287 lot C), in three steps.
 *
 * [factor] is a multiplier applied to the app `Typography` (font size + line height of every
 * role) on top of the OS font-zoom — never instead of it. So the effective on-screen size is
 * `sp_role × factor × systemFontScale`: the preset modulates the reading size additively and
 * always respects the accessibility zoom.
 *
 * - [S] slightly smaller (0.9×) for users who want denser text.
 * - [M] (default) the M3 reference sizes, identity multiplier — no scaling work at all.
 * - [L] larger (1.15×) for comfortable reading.
 *
 * The [factor] is pure data (no Compose dependency), so it lives in `:core:domain` and is read by
 * the theme layer. Persisted by [name] and observed at the app root, modelled on [ThemeMode].
 */
enum class FontScalePreference(val factor: Float) {
    S(0.9f),
    M(1.0f),
    L(1.15f),
}
