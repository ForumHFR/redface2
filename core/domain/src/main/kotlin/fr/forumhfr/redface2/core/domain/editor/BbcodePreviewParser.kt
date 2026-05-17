package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.PostContent

/**
 * Capability exposed to editor features: turn raw BBCode written by the user into a
 * [PostContent] suitable for rendering via `PostRenderer`.
 *
 * The actual parsing lives in `:core:parser`; this interface keeps `:feature:editor`
 * shielded from the implementation layer per the Konsist boundary rules.
 */
fun interface BbcodePreviewParser {
    fun parsePreview(bbcode: String): PostContent
}
