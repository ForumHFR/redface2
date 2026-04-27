package fr.forumhfr.redface2.core.network.cookie

import kotlinx.coroutines.flow.Flow
import okhttp3.Cookie

/**
 * Persistence facade for HFR session cookies. The production implementation lives in
 * :core:data and is backed by an unencrypted DataStore<Preferences>. Cf. ADR-002 — local
 * cookie encryption was removed because the password transits in plaintext POST anyway,
 * making any client-side crypto layer redundant against a runtime adversary; FBE +
 * sandbox + allowBackup="false" cover the realistic threat model.
 *
 * The interface intentionally exposes a Flow rather than synchronous reads: PersistentCookieJar
 * must answer OkHttp's blocking loadForRequest synchronously, so it maintains an in-memory
 * snapshot driven by this flow. Save/clear are suspending — they are called from
 * saveFromResponse via a coroutine launched on a Singleton-scoped scope.
 */
interface CookieStore {
    /**
     * Cold flow that emits the persisted cookies on subscription, then re-emits the full list
     * on every save/clear. Expired cookies are filtered out at read time so the consumer
     * never has to handle them explicitly.
     */
    fun observe(): Flow<List<Cookie>>

    /** Replaces the persisted cookies with the given list. Empty list is equivalent to [clear]. */
    suspend fun save(cookies: List<Cookie>)

    /** Convenience for save(emptyList()). Idempotent. */
    suspend fun clear()
}
