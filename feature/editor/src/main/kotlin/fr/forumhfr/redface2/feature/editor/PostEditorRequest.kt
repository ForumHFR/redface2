package fr.forumhfr.redface2.feature.editor

/**
 * Plain request payload assisted-injected into [PostEditorViewModel]. Mirrors the
 * `PostEditorRoute` NavKey from `:app/navigation` without leaking Navigation 3
 * types into the feature module.
 *
 * Phase 2C-A (#145) extends the payload with [page] and [subcat] : both are
 * required by HFR's `message.php` reply form contract (cf.
 * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). The caller (TopicScreen)
 * is responsible for supplying real values; passing `null` here implies "Phase
 * 2C wiring not yet ready for this entry point" and the editor stays in a
 * read-only / local-preview-only mode for that session.
 */
data class PostEditorRequest(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int?,
    val numreponse: Int?,
    val page: Int?,
    val subcat: Int?,
)
