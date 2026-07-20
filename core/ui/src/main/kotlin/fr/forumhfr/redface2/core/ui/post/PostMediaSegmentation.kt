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
    TODO("Lot 1A - TDD red")
}
