package fr.forumhfr.redface2.feature.editor

import kotlinx.serialization.Serializable

/**
 * Operating mode for a topic-level form (subject + cat/subcat + content + poll).
 *
 * The topic form is **declared** in Phase 2B-A as a route placeholder so navigation
 * intent is fixed, but the full form implementation arrives with #148/#149 in Phase
 * 2D/2E. `New` and `EditFirstPost` share most fields but differ on cat lockdown:
 * `EditFirstPost` keeps `cat` read-only because HFR forbids cross-category moves.
 */
@Serializable
enum class TopicFormMode {
    New,
    EditFirstPost,
}
