package fr.forumhfr.redface2.feature.topic

/**
 * #1301 — identifies the refresh operation currently owning the topic page.
 *
 * [TopicRefreshKind.Manual] drives the Material 3 pull-to-refresh spinner.
 * [TopicRefreshKind.PostSubmit] keeps the dedicated under-bar progress feedback visible from the
 * submit acknowledgement until the refreshed content, redirect included, reaches a terminal state.
 * [TopicRefreshKind.PostSubmitJump] keeps that progress feedback for the explicit « Y aller »
 * refresh without replaying the acknowledgement of the submit that preceded it.
 */
enum class TopicRefreshKind {
    None,
    Manual,
    PostSubmit,
    PostSubmitJump,
}

/** #1301 — both submit-owned refreshes use the dedicated under-bar progress indicator. */
internal fun TopicRefreshKind.isPostSubmitRefresh(): Boolean =
    this == TopicRefreshKind.PostSubmit || this == TopicRefreshKind.PostSubmitJump
