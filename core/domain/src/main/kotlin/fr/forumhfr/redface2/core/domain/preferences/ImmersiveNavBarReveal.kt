package fr.forumhfr.redface2.core.domain.preferences

/**
 * #518 follow-up — when, in full-screen mode (the Android system navigation bar hidden via
 * `hideSystemNavBar`), the hidden bar should be revealed again from inside the app, based on the
 * reading scroll position. Only meaningful while `hideSystemNavBar` is on.
 *
 * - [MANUAL] (default) keeps the historical #518 behaviour: the bar stays hidden and is only
 *   revealed by the Android transient swipe-from-bottom gesture.
 * - [AT_BOTTOM] reveals the bar when the topic is scrolled to the very bottom (nothing left to
 *   scroll forward), and hides it again as soon as the reader scrolls back up — the least intrusive
 *   auto-reveal.
 * - [ON_SCROLL_UP] reveals the bar whenever the reader scrolls UP (or is at the bottom) and hides it
 *   while scrolling down — mirrors the top-bar auto-hide (#285), the most eager auto-reveal.
 *
 * Persisted by its [name] like [ThemeMode] / [DisplayDensity]; observed at the app root.
 */
enum class ImmersiveNavBarReveal {
    MANUAL,
    AT_BOTTOM,
    ON_SCROLL_UP,
}

/**
 * Pure policy mapping a [ImmersiveNavBarReveal] mode + the current topic scroll facts onto whether the
 * system navigation bar should be revealed right now. Extracted so the decision is unit-testable and
 * lives in one place (the app root reads scroll facts reported by the topic screen and applies this).
 *
 * @param atBottom the topic list cannot scroll forward any further (the last post is fully reached).
 * @param scrollingUp the most recent scroll movement was towards the top of the list.
 */
fun shouldRevealNavBar(
    mode: ImmersiveNavBarReveal,
    atBottom: Boolean,
    scrollingUp: Boolean,
): Boolean = when (mode) {
    ImmersiveNavBarReveal.MANUAL -> false
    ImmersiveNavBarReveal.AT_BOTTOM -> atBottom
    ImmersiveNavBarReveal.ON_SCROLL_UP -> atBottom || scrollingUp
}
