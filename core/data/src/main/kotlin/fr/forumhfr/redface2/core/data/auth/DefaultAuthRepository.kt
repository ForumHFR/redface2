package fr.forumhfr.redface2.core.data.auth

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.auth.AuthRemoteDataSource
import fr.forumhfr.redface2.core.network.cookie.PersistentCookieJar
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val remote: AuthRemoteDataSource,
    private val cookieJar: PersistentCookieJar,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    /**
     * Authoritative auth state, derived from the runtime cookie cache exposed by
     * [PersistentCookieJar.state]. Reading from the jar (not the underlying DataStore)
     * guarantees that:
     *
     * - login success propagates synchronously: PersistentCookieJar.saveFromResponse updates
     *   the cache before this flow emits, so observeAuthState() flips to Authenticated as
     *   soon as the OkHttp response is processed — no DataStore round-trip race.
     * - cold start with a persisted session never emits a transient `Anonymous`: the jar's
     *   cache is `null` until the store collector fires, and `filterNotNull()` waits for the
     *   first non-null emission. Consumers see `Authenticated(pseudo)` directly when
     *   reopening the app on an active session.
     */
    override fun observeAuthState(): Flow<AuthState> = cookieJar.state
        .filterNotNull()
        .map { cookies ->
            val mdUser = cookies.firstOrNull { it.name == COOKIE_MD_USER && it.value.isNotBlank() }
            if (mdUser != null) {
                // #479 — the numeric id (`md_id`) is captured here, at the same single point where the
                // session pseudo is decoded, so every consumer (account badge avatar, …) can fetch the
                // public profile by id without re-reading the cookie jar. Null-safe: a session without
                // `md_id` (older cookie set) still authenticates — the avatar just falls back to the
                // pseudo initial. The auth verdict depends ONLY on `md_user` (cf. AuthRemoteDataSource).
                val userId = cookies.firstOrNull { it.name == COOKIE_MD_ID }?.value?.toIntOrNull()
                AuthState.Authenticated(decodePseudo(mdUser.value), userId)
            } else {
                AuthState.Anonymous
            }
        }
        .distinctUntilChanged()

    /**
     * Cookie persistence is *explicit*, not implicit: AuthRemoteDataSource POSTs through a
     * derived OkHttp instance with `cookieJar(NO_COOKIES).followRedirects(false)` so HFR's
     * Set-Cookie headers are staged on the response only — never written to disk by the
     * CookieJar machinery. The remote then commits them to the @AuthenticatedClient's
     * PersistentCookieJar (in-memory cache + DataStore) **iff** classify() returns
     * Authenticated. Failed logins (InvalidCredentials, RateLimited, Unknown, Network) leave
     * no cookie behind, fixing the "rejected login installs a half-valid session" risk.
     *
     * The Result returned here is the same classification — useful for LoginViewModel's UX
     * path. observeAuthState() flips to Authenticated on the next CookieJar emission, which
     * happens synchronously inside that explicit commit.
     */
    override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
        withContext(ioDispatcher) { remote.login(pseudo, password) }

    /**
     * Clears the cookie jar's runtime cache synchronously *and* schedules the underlying
     * store wipe. observeAuthState() flips to Anonymous on the same coroutine frame.
     */
    override suspend fun logout() {
        withContext(ioDispatcher) { cookieJar.clear() }
    }

    private companion object {
        const val COOKIE_MD_USER = "md_user"

        /** HFR's numeric user-id cookie, set alongside `md_user`/`md_pass` on login (#479). */
        const val COOKIE_MD_ID = "md_id"

        /**
         * #260 — HFR stores the pseudo in the `md_user` cookie form-urlencoded, so a pseudo with a
         * space arrives as e.g. `Dintr-un+lemn`. Decode it here, at the single capture point for the
         * displayed session pseudo, so every consumer (account menu, …) sees the real pseudo rather
         * than each display site having to decode. `URLDecoder` also handles `%XX`; the runCatching
         * fallback (just `+` → space) guards a malformed `%` sequence — HFR's pseudo charset
         * `[a-zA-Z0-9 _-]` never form-encodes anything but the space, so this is belt-and-braces.
         */
        fun decodePseudo(raw: String): String =
            runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }
                .getOrDefault(raw.replace('+', ' '))
    }
}
