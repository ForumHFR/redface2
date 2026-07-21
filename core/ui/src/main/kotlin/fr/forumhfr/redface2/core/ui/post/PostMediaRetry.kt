package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline

/**
 * #960 (§6) — public seam for the hosting screens (:feature modules cannot see the internal
 * ledger): retry the media of [urls] whose last attempt FAILED. Strictly scoped (Sol r3, lock #1):
 * only the provided urls are visited, and only those actually carrying a failure are bumped — a
 * healthy or in-flight url is untouched, so a screen refresh never re-decodes what already works.
 * Call it with the media urls of the posts the gesture refreshes ([collectPostMediaUrls]).
 *
 * Replaces the pre-#960 process-wide protocol (`clearPostMediaMeasurementFailures()` + a
 * screen-owned refresh-generation bump re-keying EVERY painter of the screen).
 */
fun retryFailedPostMedia(urls: Set<String>) {
    ProcessMediaAttemptLedger.instance.retryFailedUrls(urls)
}

/**
 * #960 (§6) — every media url [content] can render: inline images and smileys (recursively
 * through the styled/link inline containers) plus standalone image blocks, through quote/spoiler
 * nesting. Deliberately a SUPERSET of the measurable urls (cc-images and builtin smileys are
 * never probed but their PAINTER can fail): [retryFailedPostMedia] only acts on recorded
 * failures, so over-collecting is free.
 */
fun collectPostMediaUrls(content: PostContent): Set<String> {
    val urls = HashSet<String>()
    collectBlockMediaUrlsInto(content.blocks, urls)
    return urls
}

private fun collectBlockMediaUrlsInto(blocks: List<PostBlock>, urls: MutableSet<String>) {
    blocks.forEach { block ->
        when (block) {
            is PostBlock.Paragraph -> collectInlineMediaUrlsInto(block.inlines, urls)
            is PostBlock.Quote -> collectBlockMediaUrlsInto(block.content.blocks, urls)
            is PostBlock.Spoiler -> collectBlockMediaUrlsInto(block.content.blocks, urls)
            is PostBlock.Image -> urls.add(block.url)
            is PostBlock.Fixed, is PostBlock.CodeBlock -> Unit
        }
    }
}

private fun collectInlineMediaUrlsInto(inlines: List<PostInline>, urls: MutableSet<String>) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.InlineImage -> urls.add(inline.url)
            is PostInline.Smiley -> inline.imageUrl?.let(urls::add)
            is PostInline.Strong -> collectInlineMediaUrlsInto(inline.children, urls)
            is PostInline.Emphasis -> collectInlineMediaUrlsInto(inline.children, urls)
            is PostInline.Underline -> collectInlineMediaUrlsInto(inline.children, urls)
            is PostInline.Strike -> collectInlineMediaUrlsInto(inline.children, urls)
            is PostInline.Color -> collectInlineMediaUrlsInto(inline.children, urls)
            is PostInline.Link -> collectInlineMediaUrlsInto(inline.children, urls)
            else -> Unit
        }
    }
}
