package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock

/**
 * #239 — count, per post `numreponse`, how many DISTINCT posts on the current page cite it.
 *
 * A "citation" is a [PostBlock.Quote] carrying the cited post's `numreponse`. We descend into
 * spoilers (a quote can sit inside a `[spoiler]`) but NOT into a quote's own content: a quote
 * re-quoted inside another quote is part of the quoted material, not a fresh citation by the current
 * post. De-duplicated per post, so quoting the same post twice in one message counts once — the badge
 * reads « cité N fois » meaning "by N posts of this page".
 *
 * Scope is the loaded page only: a citation from another page is not counted (that page is not in
 * memory). This is an intentional, documented limitation of the per-page reading badge; a global
 * count would need a server-side index HFR does not expose.
 */
internal fun citationCountsByNumreponse(posts: List<Post>): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    posts.forEach { post ->
        post.citedNumreponses().forEach { cited ->
            counts[cited] = (counts[cited] ?: 0) + 1
        }
    }
    return counts
}

/** Distinct `numreponse`s this post directly cites (top-level quotes + quotes inside spoilers). */
private fun Post.citedNumreponses(): Set<Int> {
    val acc = LinkedHashSet<Int>()
    collectDirectCitations(content.blocks, acc)
    return acc
}

private fun collectDirectCitations(blocks: List<PostBlock>, acc: MutableSet<Int>) {
    blocks.forEach { block ->
        when (block) {
            // Record the cited post but do NOT recurse into block.content: a quote nested inside this
            // quote is quoted material, not a fresh citation by the current post.
            is PostBlock.Quote -> block.numreponse?.let(acc::add)
            is PostBlock.Spoiler -> collectDirectCitations(block.content.blocks, acc)
            else -> Unit
        }
    }
}
