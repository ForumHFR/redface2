package fr.forumhfr.redface2.core.ui.post

/**
 * #983 — bottom edge a `flat` [PostCardShell] is asked to draw.
 *
 * Only the owner of the post SEQUENCE knows what follows a given post, so it owns this decision and
 * the shell merely renders it (the shell sees one post at a time and cannot tell a post↔post seam
 * from a post↔separator one).
 *
 * Named rather than a boolean parameter because the two values are not the « on/off » of a feature
 * but two structural states of a seam: who closes this post.
 *
 * Lives in its own file because it is the only top-level class of the `PostCardShell` API surface
 * (detekt `MatchingDeclarationName` would otherwise demand the shell's file be renamed after it).
 */
enum class PostCardShellFlatBottomEdge {
    /** The post closes itself with the hairline. The default — what `flat` has always done. */
    HAIRLINE,

    /**
     * No hairline: either the next rendered element brings its own boundary (a separator rule, an
     * island's card border — drawing the hairline would double the trait), or the post sequence ends
     * here and a dangling rule would hang above the list's bottom inset.
     */
    NONE,
}
