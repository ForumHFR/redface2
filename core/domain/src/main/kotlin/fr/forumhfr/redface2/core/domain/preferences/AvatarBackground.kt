package fr.forumhfr.redface2.core.domain.preferences

/**
 * Background treatment of the GLOBAL account avatar badge in the top bar (#718). The badge « PP »
 * (round) shows on every main screen's top bar; this controls what sits BEHIND the avatar/initial:
 *
 * - [Container] — the theme container colour (`surfaceContainerHigh`, the default since #603/#665):
 *   the badge blends into the top-bar right container.
 * - [Transparent] — no fill: the avatar/initial sits directly on the bar. Applies to both the photo
 *   and the pseudo-initial fallback (the option must not « lie » by only being transparent when an
 *   avatar is set).
 *
 * Pure domain enum (no Android / Compose) so it can be persisted in a preference and read by the
 * account badge without dragging UI types into the model layer. GLOBAL (one value everywhere), like
 * [MarkerStyle].
 */
enum class AvatarBackground {
    Container,
    Transparent,
}
