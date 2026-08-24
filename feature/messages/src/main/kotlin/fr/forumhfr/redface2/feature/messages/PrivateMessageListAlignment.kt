package fr.forumhfr.redface2.feature.messages

/**
 * #1040 lot 6 — page whose CONTENT and scroll POSITION are both represented by the shared MP
 * `LazyListState`.
 *
 * An in-place page switch publishes the new content before Compose can execute its landing scroll.
 * During that window the canonical page already names B while the list still carries A's offset. A
 * late fling settle, a second page tap, or another position read must therefore not store those
 * coordinates under B. [onLandingApplied] closes the window only after the unique landing effect
 * completed; [shouldPersist] gates every anchor capture until then.
 *
 * Plain class: it is read only from event/effect lambdas and never drives composition. The screen
 * recreates it when the authenticated pseudo changes, so an equal page number in a new account is
 * unaligned until that account's own landing completes.
 */
internal class PrivateMessageListAlignment {

    var alignedPage: Int? = null
        private set

    fun onLandingApplied(page: Int) {
        alignedPage = page
    }

    fun shouldPersist(canonicalPage: Int, isLoaded: Boolean): Boolean =
        isLoaded && alignedPage == canonicalPage
}

/** Single gate shared by tap-time departure and scroll-settle anchor captures. */
internal fun shouldPersistPrivateMessageAnchor(
    alignment: PrivateMessageListAlignment,
    canonicalPage: Int,
    isLoaded: Boolean,
    isScrollbarDragging: Boolean,
    isZoomPositionMutationInProgress: Boolean,
): Boolean = alignment.shouldPersist(canonicalPage, isLoaded) &&
    !isScrollbarDragging &&
    !isZoomPositionMutationInProgress
