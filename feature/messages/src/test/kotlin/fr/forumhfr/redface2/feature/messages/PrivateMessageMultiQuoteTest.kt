package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateMessageMultiQuoteTest {

    @Test
    fun `selection keeps page numreponse ref author and excerpt`() {
        val message = message(numreponse = 202, author = "Alice", quoteRef = 7)

        val selection = message.toPrivateMessageQuoteSelectionOrNull(page = 4)

        assertEquals(QuoteLocator(page = 4, numreponse = 202, ref = 7), selection?.locator)
        assertEquals("Alice", selection?.author)
        assertEquals("Corps du message", selection?.excerpt)
    }

    @Test
    fun `selection fails closed without a positive ref`() {
        assertNull(message(numreponse = 202, author = "Alice", quoteRef = null)
            .toPrivateMessageQuoteSelectionOrNull(page = 4))
        assertNull(message(numreponse = 202, author = "Alice", quoteRef = 0)
            .toPrivateMessageQuoteSelectionOrNull(page = 4))
    }

    @Test
    fun `blocking an author finds their selections across pages canonically`() {
        val selections = listOf(
            selection(numreponse = 101, page = 1, author = "Alice"),
            selection(numreponse = 202, page = 4, author = "Bob"),
            selection(numreponse = 303, page = 8, author = "ALICE"),
        )

        assertEquals(
            setOf(101, 303),
            hiddenMultiQuoteNumreponses(
                selections = selections,
                hiddenNumreponses = emptySet(),
                blockedQuoteAuthors = setOf("alice"),
            ),
        )
    }

    private fun selection(numreponse: Int, page: Int, author: String): QuoteSelection = QuoteSelection(
        locator = QuoteLocator(page = page, numreponse = numreponse, ref = 1),
        author = author,
        excerpt = "Extrait",
    )

    private fun message(numreponse: Int, author: String, quoteRef: Int?): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(listOf(PostInline.Text("Corps du message"))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quoteRef = quoteRef,
        quotedAuthors = emptyList(),
        postIndex = null,
    )
}
