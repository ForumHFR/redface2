package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicActionGatesTest {

    @Test
    fun `quote action follows topic canReply and ignores missing quoteRef`() {
        val postWithNoParsedQuoteLink = post(quoteRef = null)

        assertTrue(
            "postable topics can quote by numreponse even when the toolbar quoteRef was obfuscated",
            shouldShowQuoteAction(topic(canReply = true, posts = listOf(postWithNoParsedQuoteLink))),
        )
        assertFalse(
            "read-only topics must not expose quote, regardless of per-post data",
            shouldShowQuoteAction(topic(canReply = false, posts = listOf(postWithNoParsedQuoteLink))),
        )
    }

    @Test
    fun `edit action still requires both edit link and postable topic`() {
        val editablePost = post(isEditable = true)

        assertTrue(shouldShowEditAction(topic(canReply = true, posts = listOf(editablePost)), editablePost))
        assertFalse(shouldShowEditAction(topic(canReply = false, posts = listOf(editablePost)), editablePost))
        assertFalse(shouldShowEditAction(topic(canReply = true), post(isEditable = false)))
    }

    private fun topic(
        canReply: Boolean,
        posts: List<Post> = listOf(post()),
    ): Topic = Topic(
        cat = 32,
        post = 7,
        subcat = if (canReply) 0 else Topic.SUBCAT_UNKNOWN,
        title = "Topic IA",
        posts = posts,
        page = 1,
        totalPages = 1,
        isFirstPostOwner = false,
        poll = null,
        canReply = canReply,
    )

    private fun post(
        isEditable: Boolean = false,
        quoteRef: Int? = 1,
    ): Post = Post(
        numreponse = 16244,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = isEditable,
        isOwnPost = isEditable,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = quoteRef,
        profileId = null,
    )
}
