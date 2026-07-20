package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostInline

/**
 * #876/#956 (Lot 1A) — pure paragraph partition into [ParagraphSegment.InlineSegment] (prose)
 * and [ParagraphSegment.MediaRun] (block gallery), per the frozen images contract v1.4 §2 :
 * the inline/block decision is taken on the AST STRUCTURE alone — measured dimensions never
 * participate (they only size, §3, later lots).
 *
 * This policy is NOT wired to any renderer in this lot (no production call-site) : it is the
 * mechanical foundation for the segmented renderer (Lot 1B). Types are DERIVED, UI-side data —
 * the persisted [fr.forumhfr.redface2.core.model.PostContent] model (pinned for Room) is
 * untouched.
 *
 * Contract rules implemented (v1.4 §2.1/§2.2) :
 * - A MediaRun is a MAXIMAL sequence of : content images (cc-image marker excluded, #256) ;
 *   [PostInline.LineBreak] ; blank [PostInline.Text] ; style wrappers (Strong/Emphasis/
 *   Underline/Strike/Color) recursively containing only such members ; links whose useful
 *   descendants are EXCLUSIVELY images (the `href` stays attached to each image).
 * - Run boundaries : non-blank text, smileys (#175), cc-images, textual or MIXED text+image
 *   links (G3 : the whole link stays inline prose), any other inline, paragraph frontiers.
 *   A link carrying NO image is never a run member nor silently dropped — it stays prose.
 * - Decision : a run with ≥ 2 images renders BLOCK, always. A single-image run renders BLOCK
 *   only when ISOLATED (G1) : on the ORIGINAL sequence — before any separator consumption —
 *   the image's nearest non-blank neighbour on EACH side is a paragraph frontier or a
 *   [PostInline.LineBreak] (cadrage Sol #956 : `text+BR+image+BR+text` → block ;
 *   `image+text` → inline ; `frontier+BR` counts as isolating).
 * - Separator consumption : LineBreaks/blanks absorbed by (or immediately bordering) a BLOCK
 *   run are consumed — replaced by the single §4 spacing at render time, multi-br included.
 *   Around an image that stays inline they are preserved verbatim inside the prose.
 * - Quotes/spoilers partition their own paragraphs : recursion is the CALLER's duty (Lot 1B),
 *   this function only ever sees one paragraph's inline list.
 */
internal sealed interface ParagraphSegment {
    /** Prose slice, rendered as today's inline flow (text, smileys, links, inline images…). */
    data class InlineSegment(val inlines: List<PostInline>) : ParagraphSegment

    /** Block gallery : each image gets its own centred line, §4 spacing between them. */
    data class MediaRun(val images: List<RunImage>) : ParagraphSegment
}

/** One block-rendered image ; [linkUrl] is the enclosing image-only link's href, if any. */
internal data class RunImage(
    val image: PostInline.InlineImage,
    val linkUrl: String?,
)

internal fun partitionParagraph(inlines: List<PostInline>): List<ParagraphSegment> {
    if (inlines.isEmpty()) return emptyList()

    val segments = mutableListOf<ParagraphSegment>()
    val prose = mutableListOf<PostInline>()
    var index = 0

    fun flushProse() {
        if (prose.isNotEmpty()) {
            segments += ParagraphSegment.InlineSegment(prose.toList())
            prose.clear()
        }
    }

    while (index < inlines.size) {
        val inline = inlines[index]
        if (!isRunMember(inline)) {
            prose += inline
            index++
            continue
        }
        // Maximal member run starting here.
        var end = index
        while (end < inlines.size && isRunMember(inlines[end])) end++
        val members = inlines.subList(index, end)
        val images = members.flatMap(::collectRunImages)

        when {
            // A breaks/blanks-only stretch is NOT a run (§2.2) : plain prose separators.
            images.isEmpty() -> prose += members

            // Run of ≥ 2 images → BLOCK, always (I2.1). Absorbed separators are consumed.
            images.size >= 2 -> {
                flushProse()
                segments += ParagraphSegment.MediaRun(images)
            }

            // Singleton : BLOCK only when isolated (G1) on the ORIGINAL sequence — the image's
            // nearest non-blank neighbour on each side is a paragraph frontier or a LineBreak.
            isSingletonIsolated(inlines, members, index) -> {
                flushProse()
                segments += ParagraphSegment.MediaRun(images)
            }

            // Non-isolated singleton : the image stays INLINE — every member (breaks and blanks
            // included) is re-emitted verbatim into the surrounding prose (§2.2, preservation).
            else -> prose += members
        }
        index = end
    }
    flushProse()
    return segments
}

/**
 * G1 isolation, evaluated on the ORIGINAL paragraph sequence before any consumption : from the
 * single image of the run, walk outwards skipping ONLY blank text ; both sides must reach a
 * [PostInline.LineBreak] or the paragraph frontier (cadrage #956, condition 1).
 */
private fun isSingletonIsolated(
    inlines: List<PostInline>,
    members: List<PostInline>,
    runStart: Int,
): Boolean {
    val imageOffset = members.indexOfFirst { collectRunImages(it).isNotEmpty() }
    val imageIndex = runStart + imageOffset

    fun qualifies(from: Int, step: Int, frontier: Int): Boolean {
        var i = from
        while (i != frontier && (inlines[i] as? PostInline.Text)?.value?.isBlank() == true) i += step
        return i == frontier || inlines[i] is PostInline.LineBreak
    }
    return qualifies(imageIndex - 1, -1, -1) && qualifies(imageIndex + 1, +1, inlines.size)
}

/** §2.1 membership. cc-images (#256) and smileys (#175) are boundaries, never members. */
private fun isRunMember(inline: PostInline): Boolean = when (inline) {
    is PostInline.LineBreak -> true
    is PostInline.Text -> inline.value.isBlank()
    is PostInline.InlineImage -> !isCcImageUrl(inline.url)
    is PostInline.Strong -> inline.children.all(::isRunMember)
    is PostInline.Emphasis -> inline.children.all(::isRunMember)
    is PostInline.Underline -> inline.children.all(::isRunMember)
    is PostInline.Strike -> inline.children.all(::isRunMember)
    is PostInline.Color -> inline.children.all(::isRunMember)
    // A link is a member only when its useful content is EXCLUSIVELY images (wrappers/blanks/
    // breaks admitted) AND it carries at least one : a mixed or textual link is a boundary (G3),
    // an image-less link is plain prose — never a run member, never dropped (cadrage cond. 4).
    is PostInline.Link ->
        inline.children.all(::isLinkImageContent) && inline.children.any { hasContentImage(it) }
    else -> false
}

/** Link-content check : like membership but links cannot nest, and cc disqualifies. */
private fun isLinkImageContent(inline: PostInline): Boolean = when (inline) {
    is PostInline.LineBreak -> true
    is PostInline.Text -> inline.value.isBlank()
    is PostInline.InlineImage -> !isCcImageUrl(inline.url)
    is PostInline.Strong -> inline.children.all(::isLinkImageContent)
    is PostInline.Emphasis -> inline.children.all(::isLinkImageContent)
    is PostInline.Underline -> inline.children.all(::isLinkImageContent)
    is PostInline.Strike -> inline.children.all(::isLinkImageContent)
    is PostInline.Color -> inline.children.all(::isLinkImageContent)
    else -> false
}

private fun hasContentImage(inline: PostInline): Boolean = when (inline) {
    is PostInline.InlineImage -> !isCcImageUrl(inline.url)
    is PostInline.Strong -> inline.children.any(::hasContentImage)
    is PostInline.Emphasis -> inline.children.any(::hasContentImage)
    is PostInline.Underline -> inline.children.any(::hasContentImage)
    is PostInline.Strike -> inline.children.any(::hasContentImage)
    is PostInline.Color -> inline.children.any(::hasContentImage)
    is PostInline.Link -> inline.children.any(::hasContentImage)
    else -> false
}

/** Flattens a member into its ordered [RunImage]s, attaching the enclosing link href. */
private fun collectRunImages(inline: PostInline, linkUrl: String? = null): List<RunImage> =
    when (inline) {
        is PostInline.InlineImage ->
            if (isCcImageUrl(inline.url)) emptyList() else listOf(RunImage(inline, linkUrl))
        is PostInline.Link -> inline.children.flatMap { collectRunImages(it, inline.url) }
        is PostInline.Strong -> inline.children.flatMap { collectRunImages(it, linkUrl) }
        is PostInline.Emphasis -> inline.children.flatMap { collectRunImages(it, linkUrl) }
        is PostInline.Underline -> inline.children.flatMap { collectRunImages(it, linkUrl) }
        is PostInline.Strike -> inline.children.flatMap { collectRunImages(it, linkUrl) }
        is PostInline.Color -> inline.children.flatMap { collectRunImages(it, linkUrl) }
        else -> emptyList()
    }
