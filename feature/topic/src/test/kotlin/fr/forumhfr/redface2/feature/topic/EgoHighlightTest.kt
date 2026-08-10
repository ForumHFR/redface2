package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EgoHighlightTest {

    @Test
    fun `anonymous sessions never expose EgoPost even with a persisted own bit`() {
        val post = post(author = "XaTriX", isOwnPost = true)

        val canonical = deriveEgoCanonicalPseudo(
            enabled = true,
            isAuthenticated = false,
            connectedPseudo = "XaTriX",
        )

        assertFalse(isEgoPost(post, egoCanonicalPseudo = canonical))
    }

    @Test
    fun `a stale own bit from account A does not match account B`() {
        val cachedPostFromAccountA = post(author = "AccountA", isOwnPost = true)

        assertFalse(
            isEgoPost(
                cachedPostFromAccountA,
                egoCanonicalPseudo = deriveEgoCanonicalPseudo(
                    enabled = true,
                    isAuthenticated = true,
                    connectedPseudo = "AccountB",
                ),
            ),
        )
    }

    @Test
    fun `session author matching is canonicalized`() {
        val post = post(author = "  XaaT\u00A0  ")

        assertTrue(isEgoPost(post, egoCanonicalPseudo = "xaat"))
    }

    @Test
    fun `session matching detects an own post when the HFR toolbar bit is absent`() {
        val post = post(author = "XaTriX", isOwnPost = false)

        assertTrue(isEgoPost(post, egoCanonicalPseudo = "xatrix"))
    }

    @Test
    fun `a post that only quotes the session user is not an EgoPost`() {
        val quotesMe = post(author = "SomeoneElse", quotedAuthors = listOf("XaTriX"))

        assertFalse(isEgoPost(quotesMe, egoCanonicalPseudo = "xatrix"))
    }

    @Test
    fun `an own post that quotes the session user remains an EgoPost`() {
        val ownAndQuotesMe = post(author = "XaTriX", quotedAuthors = listOf("XaTriX"))

        assertTrue(isEgoPost(ownAndQuotesMe, egoCanonicalPseudo = "xatrix"))
    }

    @Test
    fun `missing session pseudos never match`() {
        val post = post(author = "XaTriX", isOwnPost = true)

        assertFalse(isEgoPost(post, egoCanonicalPseudo = null))
        assertFalse(
            isEgoPost(
                post,
                egoCanonicalPseudo = deriveEgoCanonicalPseudo(
                    enabled = true,
                    isAuthenticated = true,
                    connectedPseudo = "  ",
                ),
            ),
        )
    }

    @Test
    fun `canonical pseudo derivation is disabled with its feature gate`() {
        assertNull(
            deriveEgoCanonicalPseudo(
                enabled = false,
                isAuthenticated = true,
                connectedPseudo = "XaTriX",
            ),
        )
    }

    @Test
    fun `canonical pseudo derivation rejects anonymous sessions`() {
        assertNull(
            deriveEgoCanonicalPseudo(
                enabled = true,
                isAuthenticated = false,
                connectedPseudo = "XaTriX",
            ),
        )
    }

    @Test
    fun `canonical pseudo derivation rejects blank pseudos`() {
        assertNull(
            deriveEgoCanonicalPseudo(
                enabled = true,
                isAuthenticated = true,
                connectedPseudo = " \u00A0 ",
            ),
        )
    }

    @Test
    fun `canonical pseudo derivation canonicalizes a live session pseudo`() {
        assertEquals(
            "xaat",
            deriveEgoCanonicalPseudo(
                enabled = true,
                isAuthenticated = true,
                connectedPseudo = "  XaaT\u00A0 ",
            ),
        )
    }

    private fun post(
        author: String,
        isOwnPost: Boolean = false,
        quotedAuthors: List<String> = emptyList(),
    ): Post = Post(
        numreponse = 1,
        author = author,
        date = Instant.EPOCH,
        content = PostContent(emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = isOwnPost,
        quotedAuthors = quotedAuthors,
        postIndex = 0,
    )
}
