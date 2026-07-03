package fr.forumhfr.redface2.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vague 4 (#604) lot 2 — 100% coverage of [postContentExcerpt], the pure one-line summary used by
 * the quote cards (author + excerpt captured at selection time, cadrage Codex). The contract:
 * conservative text strip — own prose only (nested quotes and spoilers excluded), formatting
 * flattened, whitespace collapsed, word-boundary truncation with a single ellipsis character.
 */
class PostContentExcerptTest {

    @Test
    fun `plain paragraphs join with a space and collapse whitespace`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(PostInline.Text("Premier   paragraphe.")),
                paragraph(PostInline.Text("Second\tparagraphe.")),
            ),
        )
        assertEquals("Premier paragraphe. Second paragraphe.", postContentExcerpt(content))
    }

    @Test
    fun `formatting inlines are flattened to their text`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Strong(listOf(PostInline.Text("gras"))),
                    PostInline.Text(" et "),
                    PostInline.Emphasis(listOf(PostInline.Text("italique"))),
                    PostInline.Text(" et "),
                    PostInline.Link(url = "https://x", children = listOf(PostInline.Text("lien"))),
                ),
            ),
        )
        assertEquals("gras et italique et lien", postContentExcerpt(content))
    }

    @Test
    fun `line breaks read as spaces`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(PostInline.Text("Ah oui"), PostInline.LineBreak, PostInline.Text("Ça limite l'efficacité")),
            ),
        )
        assertEquals("Ah oui Ça limite l'efficacité", postContentExcerpt(content))
    }

    @Test
    fun `nested quotes are excluded - the excerpt is the author's own prose`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "bitubo",
                    numreponse = 1,
                    page = 1,
                    content = PostContent(listOf(paragraph(PostInline.Text("propos cité")))),
                ),
                paragraph(PostInline.Text("Ma réponse à ça.")),
            ),
        )
        assertEquals("Ma réponse à ça.", postContentExcerpt(content))
    }

    @Test
    fun `spoilers are excluded`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Spoiler(
                    label = null,
                    content = PostContent(listOf(paragraph(PostInline.Text("la fin du film")))),
                ),
                paragraph(PostInline.Text("Sans divulgâcher :")),
            ),
        )
        assertEquals("Sans divulgâcher :", postContentExcerpt(content))
    }

    @Test
    fun `fixed and code blocks contribute their compacted text`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Fixed(text = "ligne\nfixe"),
                PostBlock.CodeBlock(text = "val x = 1\nval y = 2", language = "kotlin"),
            ),
        )
        assertEquals("ligne fixe val x = 1 val y = 2", postContentExcerpt(content))
    }

    @Test
    fun `smileys render as their textual code`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(
                    PostInline.Text("Bien vu"),
                    PostInline.Smiley(kind = SmileyKind.Builtin(code = ":jap:"), imageUrl = null),
                    PostInline.Smiley(kind = SmileyKind.Perso(name = "pierre_tramo"), imageUrl = null),
                ),
            ),
        )
        assertEquals("Bien vu :jap: [:pierre_tramo]", postContentExcerpt(content))
    }

    @Test
    fun `images inline or block are ignored`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Image(url = "https://x/img.png", description = null),
                paragraph(
                    PostInline.Text("Regarde"),
                    PostInline.InlineImage(url = "https://x/i.png", description = "desc"),
                ),
            ),
        )
        assertEquals("Regarde", postContentExcerpt(content))
    }

    @Test
    fun `truncation cuts on a word boundary and appends one ellipsis`() {
        val content = PostContent(
            blocks = listOf(
                paragraph(PostInline.Text("un deux trois quatre cinq six sept huit neuf dix")),
            ),
        )
        assertEquals("un deux trois…", postContentExcerpt(content, maxChars = 15))
    }

    @Test
    fun `truncation falls back to a hard cut when there is no space in range`() {
        val content = PostContent(
            blocks = listOf(paragraph(PostInline.Text("supercalifragilisticexpialidocious"))),
        )
        assertEquals("supercalif…", postContentExcerpt(content, maxChars = 10))
    }

    @Test
    fun `content at exactly maxChars is not truncated`() {
        val content = PostContent(blocks = listOf(paragraph(PostInline.Text("pile dix.."))))
        assertEquals("pile dix..", postContentExcerpt(content, maxChars = 10))
    }

    @Test
    fun `a post made only of images and quotes yields an empty excerpt`() {
        val content = PostContent(
            blocks = listOf(
                PostBlock.Image(url = "https://x/img.png", description = "alt"),
                PostBlock.Quote(
                    author = null,
                    numreponse = null,
                    page = null,
                    content = PostContent(listOf(paragraph(PostInline.Text("cité")))),
                ),
            ),
        )
        assertEquals("", postContentExcerpt(content))
    }

    private fun paragraph(vararg inlines: PostInline): PostBlock.Paragraph =
        PostBlock.Paragraph(inlines = inlines.toList())
}
