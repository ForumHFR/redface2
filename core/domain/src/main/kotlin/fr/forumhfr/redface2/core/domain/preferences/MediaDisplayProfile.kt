package fr.forumhfr.redface2.core.domain.preferences

/**
 * Block-GIF display profile (#973, contrat images §8 [AMENDEMENT-v1.5-2]).
 *
 * [factor] is `mEffectif` — the multiplier that replaces the §3 no-upscale ceiling (1,0) for an
 * ELIGIBLE media: a BLOCK content media whose probe-reported MIME is `image/gif`. The §3 hard
 * caps (width fraction, capBloc) always re-clamp the result, inline media and non-GIF media keep
 * the strict v1.5 no-upscale, and smileys (#175) / cc-images (#256) are out of scope entirely.
 *
 * - [S] ×1,0 — no upscale, pixel-sharp (the v1.5 behaviour).
 * - [M] (default, chosen by XaTriX 26/07) ×1,5.
 * - [L] ×2,5.
 *
 * The factors are §8 constants (they live HERE, not in §9). Pure data (no Compose dependency),
 * so it lives in `:core:domain` like [DisplayDensity] / [FontScalePreference]. Persisted by
 * [name] and read defensively (unknown stored value → [M]).
 */
enum class MediaDisplayProfile(val factor: Float) {
    S(1.0f),
    M(1.5f),
    L(2.5f),
}
