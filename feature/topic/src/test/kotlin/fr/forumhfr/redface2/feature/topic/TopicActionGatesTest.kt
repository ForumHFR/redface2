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
    fun `quote action requires a postable topic AND an authenticated session`() {
        val postWithNoParsedQuoteLink = post(quoteRef = null)
        val postable = topic(canReply = true, posts = listOf(postWithNoParsedQuoteLink))
        val readOnly = topic(canReply = false, posts = listOf(postWithNoParsedQuoteLink))

        assertTrue(
            "postable + authenticated can quote by numreponse even when the quoteRef was obfuscated",
            shouldShowQuoteAction(postable, isAuthenticated = true),
        )
        assertFalse(
            "read-only topics must not expose quote, regardless of per-post data",
            shouldShowQuoteAction(readOnly, isAuthenticated = true),
        )
        assertFalse(
            "#220 — logged-out must never see quote, even on a stale canReply=true row",
            shouldShowQuoteAction(postable, isAuthenticated = false),
        )
    }

    @Test
    fun `edit action requires edit link, a postable topic AND authentication`() {
        val editablePost = post(isEditable = true)
        val postable = topic(canReply = true, posts = listOf(editablePost))
        val readOnly = topic(canReply = false, posts = listOf(editablePost))

        assertTrue(shouldShowEditAction(postable, editablePost, isAuthenticated = true))
        assertFalse(shouldShowEditAction(readOnly, editablePost, isAuthenticated = true))
        assertFalse(shouldShowEditAction(topic(canReply = true), post(isEditable = false), isAuthenticated = true))
        assertFalse(
            "#220 — logged-out must never see edit, even on a stale canReply=true row",
            shouldShowEditAction(postable, editablePost, isAuthenticated = false),
        )
    }

    @Test
    fun `reply is enabled only when the topic is postable AND the session is authenticated (#220)`() {
        assertTrue(shouldEnableReply(topic(canReply = true), isAuthenticated = true))
        assertFalse("read-only topic", shouldEnableReply(topic(canReply = false), isAuthenticated = true))
        assertFalse("logged-out (e.g. stale cache)", shouldEnableReply(topic(canReply = true), isAuthenticated = false))
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
