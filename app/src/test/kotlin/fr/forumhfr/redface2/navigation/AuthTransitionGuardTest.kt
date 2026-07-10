package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #812 — the session-clearing effect (Messages back-stack reset, read-state caches, #291
 * write intentions) must fire on REAL session transitions only. Before this guard, the
 * effect re-ran on every activity recreation and a rotation inside an open MP conversation
 * silently kicked the user back to the list.
 */
class AuthTransitionGuardTest {

    @Test
    fun `identity keys are stable per session`() {
        assertEquals(null, authIdentityKey(null))
        assertEquals("anon", authIdentityKey(AuthState.Anonymous))
        // userId is the canonical key (survives a display-pseudo change at constant session).
        assertEquals(
            "auth:54596",
            authIdentityKey(AuthState.Authenticated(pseudo = "XaTriX", userId = 54596)),
        )
        // Older cookie sets without `md_id` still get a stable identity from the pseudo.
        assertEquals(
            "auth:pseudo:XaTriX",
            authIdentityKey(AuthState.Authenticated(pseudo = "XaTriX", userId = null)),
        )
    }

    @Test
    fun `recreation re-delivery of the same session is not a transition`() {
        assertFalse(isAuthTransition(previous = "auth:XaTriX", identity = "auth:XaTriX"))
        assertFalse(isAuthTransition(previous = "anon", identity = "anon"))
    }

    @Test
    fun `cold start first delivery is not a transition`() {
        assertFalse(isAuthTransition(previous = null, identity = "auth:XaTriX"))
        assertFalse(isAuthTransition(previous = null, identity = "anon"))
    }

    @Test
    fun `login logout and account switch are transitions`() {
        assertTrue(isAuthTransition(previous = "anon", identity = "auth:XaTriX"))
        assertTrue(isAuthTransition(previous = "auth:XaTriX", identity = "anon"))
        assertTrue(isAuthTransition(previous = "auth:XaTriX", identity = "auth:autre"))
    }
}
