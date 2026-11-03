package fr.forumhfr.redface2.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorCookieOwnerTest {
    @Test
    fun `identical decoded identities from several cookies are accepted`() {
        assertEquals("alice smith", editorCookieOwner(listOf("Alice+Smith", "ALICE%20SMITH", "alice smith")))
    }

    @Test
    fun `different decoded identities are rejected`() {
        assertNull(editorCookieOwner(listOf("Alice", "Bob")))
    }

    @Test
    fun `missing identity is rejected`() {
        assertNull(editorCookieOwner(emptyList()))
    }

    @Test
    fun `any empty or blank identity rejects the whole cookie selection`() {
        for (empty in listOf("", "+", "%20")) {
            assertNull(editorCookieOwner(listOf(empty)))
            assertNull(editorCookieOwner(listOf("Alice", empty)))
        }
    }
}
