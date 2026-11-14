package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.feature.messages.PrivateMessageSubmitResult
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1040 lot 6 — pure guards around the editor→retained-conversation handoff. */
class PrivateMessageSubmitNavigationTest {

    @Test
    fun `private edit route restores opaque coordinates without changing the public route format`() {
        val route = PrivateMessageEditRoute(threadId = 42, page = 3, numreponse = 101)
        val encoded = Json.encodeToString(route)
        assertEquals(setOf("threadId", "page", "numreponse"), Json.parseToJsonElement(encoded)
            .let { it as kotlinx.serialization.json.JsonObject }.keys)
        assertEquals(route, Json.decodeFromString<PrivateMessageEditRoute>(encoded))
        assertTrue(route.hidesNavigationSuite())
        assertEquals(PostEditorRoute(PostEditorMode.Edit, 23, 35421, 101, 3, 0),
            Json.decodeFromString<PostEditorRoute>(
                """{"mode":"Edit","cat":23,"topicId":35421,"numreponse":101,"page":3,"subcat":0}"""))
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
            result = PrivateMessageSubmitResult(eventId = 9L, page = 3),
        )

        assertTrue(pending.matches(canonicalizePseudo("xaat"), threadId = 42))
        assertFalse(pending.matches(canonicalizePseudo("bob"), threadId = 42))
        assertFalse(pending.matches(canonicalizePseudo("xaat"), threadId = 7))
        assertFalse(pending.matches(account = null, threadId = 42))
    }
}
