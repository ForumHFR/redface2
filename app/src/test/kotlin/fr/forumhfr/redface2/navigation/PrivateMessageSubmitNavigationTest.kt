package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.feature.messages.PrivateMessageSubmitResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1040 lot 6 — pure guards around the editor→retained-conversation handoff. */
class PrivateMessageSubmitNavigationTest {

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `private edit route carries only opaque coordinates without changing the public route format`() {
        val route = PrivateMessageEditRoute(threadId = 42, page = 3, numreponse = 101)
        assertEquals(
            listOf("threadId", "page", "numreponse"),
            PrivateMessageEditRoute.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(42, route.threadId)
        assertEquals(3, route.page)
        assertEquals(101, route.numreponse)
        assertTrue(route.hidesNavigationSuite())
        assertEquals(
            listOf("mode", "cat", "topicId", "numreponse", "page", "subcat", "resumeSharedDraft"),
            PostEditorRoute.serializer().descriptor.elementNames.toList(),
        )
    }

    @Test
    fun `publish guard accepts only the matching conversation directly below editor`() {
        assertTrue(
            isPrivateMessageThreadEntryFor(
                below = PrivateMessageThreadRoute(threadId = 42, page = 3),
                threadId = 42,
            ),
        )
        assertFalse(
            isPrivateMessageThreadEntryFor(
                below = PrivateMessageThreadRoute(threadId = 7, page = 3),
                threadId = 42,
            ),
        )
        assertFalse(isPrivateMessageThreadEntryFor(below = MessagesRoute, threadId = 42))
    }

    @Test
    fun `pending result is keyed by canonical account and thread`() {
        val pending = PrivateMessagePendingSubmit(
            account = canonicalizePseudo("XaAT"),
            threadId = 42,
            result = PrivateMessageSubmitResult(eventId = 9L, page = 3, account = canonicalizePseudo("XaAT")),
        )

        assertTrue(pending.matches(canonicalizePseudo("xaat"), threadId = 42))
        assertFalse(pending.matches(canonicalizePseudo("bob"), threadId = 42))
        assertFalse(pending.matches(canonicalizePseudo("xaat"), threadId = 7))
        assertFalse(pending.matches(account = null, threadId = 42))
    }
}
