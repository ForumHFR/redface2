package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CitationIndexTest {

    @Test
    fun `counts distinct citing posts per cited numreponse`() {
        val posts = listOf(
            post(1, listOf(paragraph("hello"))),
            post(2, listOf(quote(citing = 1), paragraph("re"))),
            post(3, listOf(quote(citing = 1))),
        )

        val counts = citationCountsByNumreponse(posts)

        assertEquals(2, counts[1])
        assertNull("a post nobody cites is absent from the map", counts[2])
    }

    @Test
    fun `quoting the same post twice in one message counts that message once`() {
        val posts = listOf(
            post(1, listOf(paragraph("x"))),
            post(2, listOf(quote(citing = 1), paragraph("a"), quote(citing = 1))),
        )

        assertEquals(1, citationCountsByNumreponse(posts)[1])
    }

    @Test
    fun `a quote nested inside another quote is not a fresh citation`() {
        // post 3 quotes post 2, whose quote had itself re-quoted post 1 (nested). Only 2 is cited by 3.
        val nested = PostBlock.Quote(
            author = "B",
            numreponse = 2,
            page = 1,
            content = PostContent(listOf(quote(citing = 1), paragraph("inner"))),
        )
        val posts = listOf(post(3, listOf(nested)))

        val counts = citationCountsByNumreponse(posts)

        assertEquals(1, counts[2])
        assertNull("the nested re-quote of post 1 must not count", counts[1])
    }

    @Test
    fun `a quote inside a spoiler still counts`() {
        val spoiler = PostBlock.Spoiler(
            label = null,
            content = PostContent(listOf(quote(citing = 5))),
        )
        val posts = listOf(post(6, listOf(spoiler)))

        assertEquals(1, citationCountsByNumreponse(posts)[5])
    }

    @Test
    fun `a bare quote with no numreponse is ignored`() {
        val bareQuote = PostBlock.Quote(
            author = null,
            numreponse = null,
            page = null,
            content = PostContent(emptyList()),
        )
        val posts = listOf(post(7, listOf(bareQuote)))

        assertEquals(emptyMap<Int, Int>(), citationCountsByNumreponse(posts))
    }

    private fun post(numreponse: Int, blocks: List<PostBlock>): Post = Post(
        numreponse = numreponse,
        author = "user$numreponse",
        date = Instant.EPOCH,
        content = PostContent(blocks),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private fun paragraph(text: String): PostBlock.Paragraph =
        PostBlock.Paragraph(listOf(PostInline.Text(text)))

    private fun quote(citing: Int): PostBlock.Quote =
        PostBlock.Quote(author = "u$citing", numreponse = citing, page = 1, content = PostContent(emptyList()))
}
