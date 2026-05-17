package fr.forumhfr.redface2.feature.editor

import kotlinx.serialization.Serializable

/**
 * Operating mode for a post-level editor session (no topic-form fields involved).
 *
 * Distinguishes the two HFR endpoints `bddpost.php` reply vs `bdd.php` edit, which
 * share the same `content` payload contract but differ on routing keys (`post` vs
 * `numreponse`). Phase 2B-A only renders the local editor — actual POST wiring
 * arrives with #145/#146/#147.
 */
@Serializable
enum class PostEditorMode {
    Reply,
    Edit,
}
