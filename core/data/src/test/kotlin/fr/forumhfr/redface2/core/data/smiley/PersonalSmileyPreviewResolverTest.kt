package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalSmileyPreviewResolverTest {

    private val registry = PersonalSmileyRegistry()
    private val registryStamp = registry.capture()
    private val resolver = PersonalSmileyPreviewResolver(registry)

    @Test
    fun `resolves perso smileys recursively through every nested preview container`() {
        val names = listOf(
            "paragraph",
            "strong",
            "emphasis",
            "underline",
            "strike",
            "color",
            "link",
            "quote",
            "spoiler",
        )
        names.forEach { registry.register(registryStamp, it, urlFor(it)) }
        val content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        smiley("paragraph"),
                        PostInline.Strong(listOf(smiley("strong"))),
                        PostInline.Emphasis(listOf(smiley("emphasis"))),
                        PostInline.Underline(listOf(smiley("underline"))),
                        PostInline.Strike(listOf(smiley("strike"))),
                        PostInline.Color("#123456", listOf(smiley("color"))),
                        PostInline.Link("https://example.com", listOf(smiley("link"))),
                    ),
                ),
                PostBlock.Quote(
                    author = "Alice",
                    numreponse = 42,
                    page = 3,
                    content = paragraphContent(smiley("quote")),
                ),
                PostBlock.Spoiler(
                    label = "Secret",
                    content = paragraphContent(smiley("spoiler")),
                ),
            ),
        )

        val resolved = resolver.resolve(content)

        val paragraph = resolved.blocks[0] as PostBlock.Paragraph
        assertEquals(urlFor("paragraph"), (paragraph.inlines[0] as PostInline.Smiley).imageUrl)
        assertEquals(urlFor("strong"), paragraph.inlines[1].nestedSmiley().imageUrl)
        assertEquals(urlFor("emphasis"), paragraph.inlines[2].nestedSmiley().imageUrl)
        assertEquals(urlFor("underline"), paragraph.inlines[3].nestedSmiley().imageUrl)
        assertEquals(urlFor("strike"), paragraph.inlines[4].nestedSmiley().imageUrl)
        assertEquals(urlFor("color"), paragraph.inlines[5].nestedSmiley().imageUrl)
        assertEquals(urlFor("link"), paragraph.inlines[6].nestedSmiley().imageUrl)
        assertEquals(urlFor("quote"), (resolved.blocks[1] as PostBlock.Quote).content.onlySmiley().imageUrl)
        assertEquals(urlFor("spoiler"), (resolved.blocks[2] as PostBlock.Spoiler).content.onlySmiley().imageUrl)
    }

    @Test
    fun `never replaces an existing URL and leaves misses and builtins untouched`() {
        registry.register(registryStamp, "known", "https://example.com/new.gif")
        val existing = smiley("known", imageUrl = "https://example.com/existing.gif")
        val missing = smiley("missing")
        val builtin = PostInline.Smiley(SmileyKind.Builtin(":jap:"), imageUrl = null)
        val content = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(existing, missing, builtin))),
        )

        val inlines = (resolver.resolve(content).blocks.single() as PostBlock.Paragraph).inlines

        assertEquals("https://example.com/existing.gif", (inlines[0] as PostInline.Smiley).imageUrl)
        assertNull((inlines[1] as PostInline.Smiley).imageUrl)
        assertEquals(builtin, inlines[2])
    }

    private fun paragraphContent(smiley: PostInline.Smiley): PostContent =
        PostContent(listOf(PostBlock.Paragraph(listOf(smiley))))

    private fun smiley(name: String, imageUrl: String? = null): PostInline.Smiley =
        PostInline.Smiley(SmileyKind.Perso(name), imageUrl)

    private fun urlFor(name: String): String = "https://example.com/$name.gif"

    private fun PostInline.nestedSmiley(): PostInline.Smiley = when (this) {
        is PostInline.Strong -> children.single() as PostInline.Smiley
        is PostInline.Emphasis -> children.single() as PostInline.Smiley
        is PostInline.Underline -> children.single() as PostInline.Smiley
        is PostInline.Strike -> children.single() as PostInline.Smiley
        is PostInline.Color -> children.single() as PostInline.Smiley
        is PostInline.Link -> children.single() as PostInline.Smiley
        else -> error("Expected a styled or linked smiley, got $this")
    }

    private fun PostContent.onlySmiley(): PostInline.Smiley =
        ((blocks.single() as PostBlock.Paragraph).inlines.single() as PostInline.Smiley)
}
