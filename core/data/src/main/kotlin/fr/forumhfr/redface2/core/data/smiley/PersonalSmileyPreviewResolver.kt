package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import javax.inject.Inject

/**
 * #873 — pure post-parse AST pass that resolves only missing perso-smiley image URLs.
 *
 * Keeping this lookup after `BbcodeContentParser` preserves deterministic parsing: a registry miss
 * leaves the null URL untouched, so the renderer keeps displaying the original `[:name]` token.
 */
class PersonalSmileyPreviewResolver @Inject internal constructor(
    private val registry: PersonalSmileyRegistry,
) {

    fun resolve(content: PostContent): PostContent = content.copy(
        blocks = content.blocks.map(::resolveBlock),
    )

    private fun resolveBlock(block: PostBlock): PostBlock = when (block) {
        is PostBlock.Paragraph -> block.copy(inlines = block.inlines.map(::resolveInline))
        is PostBlock.Quote -> block.copy(content = resolve(block.content))
        is PostBlock.Spoiler -> block.copy(content = resolve(block.content))
        is PostBlock.Image -> block
        is PostBlock.Fixed -> block
        is PostBlock.CodeBlock -> block
    }

    private fun resolveInline(inline: PostInline): PostInline = when (inline) {
        is PostInline.Strong -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Emphasis -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Underline -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Strike -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Color -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Link -> inline.copy(children = inline.children.map(::resolveInline))
        is PostInline.Smiley -> resolveSmiley(inline)
        is PostInline.Text -> inline
        PostInline.LineBreak -> inline
        is PostInline.InlineImage -> inline
    }

    private fun resolveSmiley(smiley: PostInline.Smiley): PostInline.Smiley {
        val resolvedUrl = (smiley.kind as? SmileyKind.Perso)
            ?.takeIf { smiley.imageUrl == null }
            ?.let { registry.resolve(it.name) }
        return if (resolvedUrl == null) smiley else smiley.copy(imageUrl = resolvedUrl)
    }
}
