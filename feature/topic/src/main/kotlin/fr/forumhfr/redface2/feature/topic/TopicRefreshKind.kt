package fr.forumhfr.redface2.feature.topic

/**
 * #1301 — identifies the refresh operation currently owning the topic page.
 *
 * [TopicRefreshKind.Manual] drives the Material 3 pull-to-refresh spinner.
 * [TopicRefreshKind.PostSubmit] keeps the dedicated under-bar progress feedback visible from the
 * submit acknowledgement until the refreshed content, redirect included, reaches a terminal state.
 */
enum class TopicRefreshKind {
    None,
    Manual,
    PostSubmit,
}
