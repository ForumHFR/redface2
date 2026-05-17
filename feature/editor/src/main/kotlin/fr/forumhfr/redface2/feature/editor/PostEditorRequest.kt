package fr.forumhfr.redface2.feature.editor

/**
 * Plain request payload assisted-injected into [PostEditorViewModel]. Mirrors the
 * `PostEditorRoute` NavKey from `:app/navigation` without leaking Navigation 3 types
 * into the feature module. Phase 2B-A only renders the local editor — actual
 * HFR-side reply / edit submission arrive with #145 / #147.
 */
data class PostEditorRequest(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int?,
    val numreponse: Int?,
)
