package fr.forumhfr.redface2.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for [postContentPlainText], the pure full-text projection used by copy actions.
 * Unlike an excerpt, it preserves the complete readable content, including quotes and spoilers.
 */
class PostContentPlainTextTest {

    @Test
    fun `paragraphs use blank separators and line breaks stay explicit`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Text("Première ligne"),
                    PostInline.LineBreak,
                    PostInline.Text("Deuxième ligne"),
                ),
                paragraph(PostInline.Text("Paragraphe suivant")),
            ),
        )

        assertEquals(
            "Première ligne\nDeuxième ligne\n\nParagraphe suivant",
            postContentPlainText(content),
        )
    }

    @Test
    fun `inline styles are flattened to their complete text`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Strong(listOf(PostInline.Text("gras"))),
                    PostInline.Text(", "),
                    PostInline.Emphasis(listOf(PostInline.Text("italique"))),
                    PostInline.Text(", "),
                    PostInline.Underline(listOf(PostInline.Text("souligné"))),
                    PostInline.Text(", "),
                    PostInline.Strike(listOf(PostInline.Text("barré"))),
                    PostInline.Text(", "),
                    PostInline.Color(
                        colorHex = "#FF0000",
                        children = listOf(PostInline.Text("coloré")),
                    ),
                ),
            ),
        )

        assertEquals("gras, italique, souligné, barré, coloré", postContentPlainText(content))
    }

    @Test
    fun `link keeps visible text and adds a distinct URL`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Link(
                        url = "https://example.test/documentation",
                        children = listOf(PostInline.Text("Documentation")),
                    ),
                ),
            ),
        )

        assertEquals(
            "Documentation (https://example.test/documentation)",
            postContentPlainText(content),
        )
    }

    @Test
    fun `link does not repeat a URL already used as visible text`() {
        val url = "https://example.test/page"
        val content = PostContent(
            blocks = listOf(
                paragraph(PostInline.Link(url = url, children = listOf(PostInline.Text(url)))),
            ),
        )

        assertEquals(url, postContentPlainText(content))
    }

    @Test
    fun `fixed and code blocks keep internal line breaks`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Fixed(text = "ligne fixe 1\nligne fixe 2"),
                PostBlock.CodeBlock(
                    text = "val answer = 42\nreturn answer",
                    language = "kotlin",
                ),
            ),
        )

        assertEquals(
            "ligne fixe 1\nligne fixe 2\n\nval answer = 42\nreturn answer",
            postContentPlainText(content),
        )
    }

    @Test
    fun `fixed block keeps consecutive empty lines`() {
        val content = PostContent(
            blocks = listOf(PostBlock.Fixed(text = "première\n\n\ntroisième")),
        )

        assertEquals("première\n\n\ntroisième", postContentPlainText(content))
    }

    @Test
    fun `code block does not require a language`() {
        val content = PostContent(
            blocks = listOf(PostBlock.CodeBlock(text = "echo plain text", language = null)),
        )

        assertEquals("echo plain text", postContentPlainText(content))
    }

    @Test
    fun `quotes recurse and a null nested author adds no attribution`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "alice",
                    numreponse = 12,
                    page = 3,
                    content = PostContent(
                        blocks = listOf(
                            paragraph(PostInline.Text("Citation extérieure")),
                            PostBlock.Quote(
                                author = null,
                                numreponse = null,
                                page = null,
                                content = PostContent(
                                    blocks = listOf(paragraph(PostInline.Text("Citation imbriquée"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "> Citation extérieure\n> \n> > Citation imbriquée\n> — alice",
            postContentPlainText(content),
        )
    }

    @Test
    fun `spoiler inside a quote stays signalled and readable`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = null,
                    numreponse = null,
                    page = null,
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Spoiler(
                                label = "fin du film",
                                content = PostContent(
                                    blocks = listOf(paragraph(PostInline.Text("Le secret."))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "> [spoiler]\n> Le secret.\n> [/spoiler]",
            postContentPlainText(content),
        )
    }

    @Test
    fun `spoiler ignores the constant HFR label`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Spoiler(
                    label = "Spoiler",
                    content = PostContent(
                        blocks = listOf(paragraph(PostInline.Text("Texte masqué"))),
                    ),
                ),
            ),
        )

        assertEquals(
            "[spoiler]\nTexte masqué\n[/spoiler]",
            postContentPlainText(content),
        )
    }

    @Test
    fun `quote and spoiler defaults are locale-neutral`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "alice",
                    numreponse = null,
                    page = null,
                    content = PostContent(
                        blocks = listOf(paragraph(PostInline.Text("Texte cité"))),
                    ),
                ),
                PostBlock.Spoiler(
                    label = null,
                    content = PostContent(
                        blocks = listOf(paragraph(PostInline.Text("Texte masqué"))),
                    ),
                ),
            ),
        )

        assertEquals(
            "> Texte cité\n> — alice\n\n[spoiler]\nTexte masqué\n[/spoiler]",
            postContentPlainText(content),
        )
    }

    @Test
    fun `block and inline images create no phantom separators`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(PostInline.Text("Avant")),
                PostBlock.Image(url = "https://example.test/block.png", description = "bloc"),
                paragraph(
                    PostInline.InlineImage(
                        url = "https://example.test/inline.png",
                        description = "inline",
                    ),
                ),
                paragraph(PostInline.Text("Après")),
            ),
        )

        assertEquals("Avant\n\nAprès", postContentPlainText(content))
    }

    @Test
    fun `message made only of images is empty`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Image(url = "https://example.test/block.png", description = null),
                paragraph(
                    PostInline.InlineImage(
                        url = "https://example.test/inline.png",
                        description = null,
                    ),
                ),
            ),
        )

        assertEquals("", postContentPlainText(content))
    }

    @Test
    fun `smileys keep builtin and personal textual codes`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Smiley(
                        kind = SmileyKind.Builtin(code = ":jap:"),
                        imageUrl = "https://example.test/jap.gif",
                    ),
                    PostInline.Text(" "),
                    PostInline.Smiley(
                        kind = SmileyKind.Perso(name = "pierre_tramo"),
                        imageUrl = null,
                    ),
                ),
            ),
        )

        assertEquals(":jap: [:pierre_tramo]", postContentPlainText(content))
    }

    @Test
    fun `empty message is empty`() {
        assertEquals("", postContentPlainText(PostContent(blocks = emptyList())))
    }

    private fun paragraph(vararg inlines: PostInline): PostBlock.Paragraph =
        PostBlock.Paragraph(inlines = inlines.toList())
}
