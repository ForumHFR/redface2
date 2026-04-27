package fr.forumhfr.redface2.core.domain.auth

import fr.forumhfr.redface2.core.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /**
     * Emits the current auth state and every subsequent change. Cold flow: each collector
     * reads the persisted cookie store on subscription, so the very first emission is never
     * Loading — it is either Anonymous (no cookies) or Authenticated(pseudo) (cookies present).
     */
    fun observeAuthState(): Flow<AuthState>

    /**
     * POSTs the login form to HFR. On success, persists the session cookies and updates the
     * observed state. The returned Result wraps either the resolved Authenticated state or a
     * typed [LoginError] — callers should not rely on the message of the underlying Throwable
     * for branching, only on the LoginError subtype.
     */
    suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated>

    /**
     * Clears the persisted cookies and resets the observed state to Anonymous. Idempotent:
     * calling logout while already Anonymous is a no-op.
     */
    suspend fun logout()
}
