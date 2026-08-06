package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicActionGatesTest {

    @Test
    fun `favorite menu action exposes resolved HFR state and fails closed (#986)`() {
        assertEquals(
            PostFavoriteAction.HIDDEN,
            favoriteActionFor(isAuthenticated = false, quoteRef = 1, state = FavoriteAtPostState.Unknown),
        )
        assertEquals(
            PostFavoriteAction.HIDDEN,
            favoriteActionFor(isAuthenticated = true, quoteRef = 0, state = FavoriteAtPostState.Unknown),
        )
        assertEquals(
            PostFavoriteAction.CHECKING,
            favoriteActionFor(isAuthenticated = true, quoteRef = 1, state = FavoriteAtPostState.Resolving),
        )
        assertEquals(
            PostFavoriteAction.ADD,
            favoriteActionFor(
                isAuthenticated = true,
                quoteRef = 1,
                state = FavoriteAtPostState.Ready(topicHasFavorite = false),
            ),
        )
        assertEquals(
            PostFavoriteAction.MOVE,
            favoriteActionFor(
                isAuthenticated = true,
                quoteRef = 1,
                state = FavoriteAtPostState.Ready(topicHasFavorite = true),
            ),
        )
        assertEquals(
            PostFavoriteAction.UNAVAILABLE,
            favoriteActionFor(isAuthenticated = true, quoteRef = 1, state = FavoriteAtPostState.Unavailable),
        )
        assertEquals(
            PostFavoriteAction.ADDING,
            favoriteActionFor(
                isAuthenticated = true,
                quoteRef = 1,
                state = FavoriteAtPostState.Adding(topicHadFavorite = false),
            ),
        )
    }

    @Test
    fun `last-read marker requires the flag-tap route AND the matching post`() {
        fun request(scrollTo: Int?, forceRefresh: Boolean) = TopicRequest(
            cat = 23,
            post = 35421,
            page = 61,
            scrollTo = scrollTo,
            forceRefresh = forceRefresh,
        )

        assertTrue(
            "#600 — flag tap resuming at the last-read post shows the marker there",
            shouldShowLastReadMarker(request(scrollTo = 42, forceRefresh = true), numreponse = 42),
        )
        assertFalse(
            "the marker belongs to the last-read post only, not its neighbours",
            shouldShowLastReadMarker(request(scrollTo = 42, forceRefresh = true), numreponse = 43),
        )
        assertFalse(
            "#699 — a citation jump carries scrollTo WITHOUT forceRefresh: no marker",
            shouldShowLastReadMarker(request(scrollTo = 42, forceRefresh = false), numreponse = 42),
        )
        assertFalse(
            "a flag opened on « 1er non-lu »/« dernière page » drops scrollTo: no marker",
            shouldShowLastReadMarker(request(scrollTo = null, forceRefresh = true), numreponse = 42),
        )
    }

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

        assertTrue(shouldShowEditAction(postable, editablePost, isAuthenticated = true, connectedPseudo = null))
        assertFalse(shouldShowEditAction(readOnly, editablePost, isAuthenticated = true, connectedPseudo = null))
        assertFalse(
            shouldShowEditAction(
                topic(canReply = true),
                post(isEditable = false),
                isAuthenticated = true,
                connectedPseudo = null,
            ),
        )
        assertFalse(
            "#220 — logged-out must never see edit, even on a stale canReply=true row",
            shouldShowEditAction(postable, editablePost, isAuthenticated = false, connectedPseudo = null),
        )
    }

    @Test
    fun `edit action falls back to ownership-by-pseudo when the toolbar is absent (#545)`() {
        // affichoutils=0 : HFR strips the toolbar → the parser reports isEditable=false even on
        // the user's own posts. The session pseudo recognises them by author instead.
        val blindOwnPost = post(isEditable = false)
        val postable = topic(canReply = true, posts = listOf(blindOwnPost))

        assertTrue(
            "own post recognised by pseudo despite a missing edit link",
            shouldShowEditAction(postable, blindOwnPost, isAuthenticated = true, connectedPseudo = "XaTriX"),
        )
        assertTrue(
            "pseudo comparison is canonical (case-insensitive)",
            shouldShowEditAction(postable, blindOwnPost, isAuthenticated = true, connectedPseudo = "xatrix"),
        )
        assertFalse(
            "someone else's post stays non-editable",
            shouldShowEditAction(postable, blindOwnPost, isAuthenticated = true, connectedPseudo = "muzah"),
        )
        assertFalse(
            "the fallback never overrides the topic lock",
            shouldShowEditAction(
                topic(canReply = false, posts = listOf(blindOwnPost)),
                blindOwnPost,
                isAuthenticated = true,
                connectedPseudo = "XaTriX",
            ),
        )
        assertTrue(
            "delete shares the fallback",
            shouldShowDeleteAction(postable, blindOwnPost, isAuthenticated = true, connectedPseudo = "XaTriX"),
        )
    }

    @Test
    fun `delete action shares the edit gate (own editable post, postable topic, authenticated)`() {
        val editablePost = post(isEditable = true)
        val postable = topic(canReply = true, posts = listOf(editablePost))
        val readOnly = topic(canReply = false, posts = listOf(editablePost))

        assertTrue(shouldShowDeleteAction(postable, editablePost, isAuthenticated = true, connectedPseudo = null))
        assertFalse(
            "read-only topic",
            shouldShowDeleteAction(readOnly, editablePost, isAuthenticated = true, connectedPseudo = null),
        )
        assertFalse(
            "non-editable post (not the author / no edit link)",
            shouldShowDeleteAction(
                topic(canReply = true),
                post(isEditable = false),
                isAuthenticated = true,
                connectedPseudo = null,
            ),
        )
        assertFalse(
            "#220 — logged-out must never see delete, even on a stale canReply=true row",
            shouldShowDeleteAction(postable, editablePost, isAuthenticated = false, connectedPseudo = null),
        )
    }

    @Test
    fun `first-post exclusion fences the delete affordance to a page-1 position`() {
        val first = post(isEditable = true, numreponse = 100)
        val second = post(isEditable = true, numreponse = 200)
        val page1 = topic(canReply = true, posts = listOf(first, second), page = 1)

        assertTrue("page-1 first post would delete the whole topic → excluded", isFirstPostOfTopic(page1, first))
        assertFalse("a later post on page 1 is deletable", isFirstPostOfTopic(page1, second))
        assertFalse(
            "the same numreponse on page 2 is never the topic's first post",
            isFirstPostOfTopic(topic(canReply = true, posts = listOf(first, second), page = 2), first),
        )
    }

    @Test
    fun `reply is enabled only when the topic is postable AND the session is authenticated (#220)`() {
        assertTrue(shouldEnableReply(topic(canReply = true), isAuthenticated = true))
        assertFalse("read-only topic", shouldEnableReply(topic(canReply = false), isAuthenticated = true))
        assertFalse("logged-out (e.g. stale cache)", shouldEnableReply(topic(canReply = true), isAuthenticated = false))
    }

    @Test
    fun `edit-first-post needs auth, FP ownership, postable real-subcat topic on page 1 (#148, #220)`() {
        val fp = post(isEditable = true)
        val ready = topic(canReply = true, posts = listOf(fp), isFirstPostOwner = true, subcat = 550, page = 1)

        assertTrue(shouldShowEditFirstPost(ready, isAuthenticated = true, connectedPseudo = null))
        assertFalse(
            "#220 — logged-out never sees Modifier-FP, even on a stale postable row",
            shouldShowEditFirstPost(ready, isAuthenticated = false, connectedPseudo = null),
        )
        assertFalse(
            "not the FP owner",
            shouldShowEditFirstPost(
                ready.copy(isFirstPostOwner = false),
                isAuthenticated = true,
                connectedPseudo = null,
            ),
        )
        assertFalse(
            "#213 — FP recategorise is not relaxed for subcat=0 (IA-style cat)",
            shouldShowEditFirstPost(ready.copy(subcat = 0), isAuthenticated = true, connectedPseudo = null),
        )
        assertFalse(
            "FP lives on page 1 only",
            shouldShowEditFirstPost(
                ready.copy(page = 2, totalPages = 2),
                isAuthenticated = true,
                connectedPseudo = null,
            ),
        )
        assertFalse(
            "read-only topic",
            shouldShowEditFirstPost(ready.copy(canReply = false), isAuthenticated = true, connectedPseudo = null),
        )
    }

    @Test
    fun `edit-first-post falls back to ownership-by-pseudo of the first post (#545)`() {
        // isFirstPostOwner is parser-derived from the FP's edit link — absent for affichoutils=0.
        val blind = topic(
            canReply = true,
            posts = listOf(post(isEditable = false)),
            isFirstPostOwner = false,
            subcat = 550,
            page = 1,
        )

        assertTrue(
            "FP owned by pseudo despite a toolbar-blind parse",
            shouldShowEditFirstPost(blind, isAuthenticated = true, connectedPseudo = "XaTriX"),
        )
        assertFalse(
            "someone else's FP stays locked",
            shouldShowEditFirstPost(blind, isAuthenticated = true, connectedPseudo = "muzah"),
        )
    }

    @Test
    fun `send private message requires auth, a real profile, and someone else's post`() {
        val other = post(profileId = 123)

        assertTrue(
            "#792 — authenticated + someone else's profiled post exposes « Envoyer un MP »",
            shouldShowSendPrivateMessage(other, isAuthenticated = true, connectedPseudo = null),
        )
        assertFalse(
            "#220 — logged-out must never see « Envoyer un MP »",
            shouldShowSendPrivateMessage(other, isAuthenticated = false, connectedPseudo = null),
        )
        assertFalse(
            "no MP to oneself",
            shouldShowSendPrivateMessage(
                post(profileId = 123, isOwnPost = true),
                isAuthenticated = true,
                connectedPseudo = null,
            ),
        )
        assertFalse(
            "#545 — no MP to oneself either when only the pseudo reveals ownership",
            shouldShowSendPrivateMessage(
                post(profileId = 123, isOwnPost = false),
                isAuthenticated = true,
                connectedPseudo = "XaTriX",
            ),
        )
        assertFalse(
            "profile-less rows (« Publicité », anonymous reads) are not messageable",
            shouldShowSendPrivateMessage(post(profileId = null), isAuthenticated = true, connectedPseudo = null),
        )
    }

    private fun topic(
        canReply: Boolean,
        posts: List<Post> = listOf(post()),
        isFirstPostOwner: Boolean = false,
        subcat: Int = if (canReply) 0 else Topic.SUBCAT_UNKNOWN,
        page: Int = 1,
    ): Topic = Topic(
        cat = 32,
        post = 7,
        subcat = subcat,
        title = "Topic IA",
        posts = posts,
        page = page,
        totalPages = page.coerceAtLeast(1),
        isFirstPostOwner = isFirstPostOwner,
        poll = null,
        canReply = canReply,
    )

    private fun post(
        isEditable: Boolean = false,
        quoteRef: Int? = 1,
        numreponse: Int = 16244,
        isOwnPost: Boolean = isEditable,
        profileId: Int? = null,
    ): Post = Post(
        numreponse = numreponse,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = isEditable,
        isOwnPost = isOwnPost,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = quoteRef,
        profileId = profileId,
    )
}
