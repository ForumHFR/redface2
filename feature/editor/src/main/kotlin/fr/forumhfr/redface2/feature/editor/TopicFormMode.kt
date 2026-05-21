package fr.forumhfr.redface2.feature.editor

import kotlinx.serialization.Serializable

/**
 * Operating mode for a topic-level form (subject + cat/subcat + content + poll).
 *
 * `New` and `EditFirstPost` share most fields but differ on routing semantics:
 * `EditFirstPost` rewrites an existing topic first post through `bdd.php`,
 * whereas `New` creates a brand-new topic through `bddpost.php`.
 */
@Serializable
enum class TopicFormMode {
    New,
    EditFirstPost,
}
