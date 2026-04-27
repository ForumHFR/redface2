package fr.forumhfr.redface2.core.data.auth

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.auth.AuthRemoteDataSource
import fr.forumhfr.redface2.core.network.cookie.PersistentCookieJar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
            if (mdUser != null) AuthState.Authenticated(mdUser.value) else AuthState.Anonymous
        }

    /**
     * The persistence side-effect happens implicitly: AuthRemoteDataSource POSTs through the
     * @AuthenticatedClient OkHttp instance, whose CookieJar (PersistentCookieJar) writes the
     * Set-Cookie headers to its in-memory cache *and* through to CookieStore. The Result we
     * return here is the immediate classification — useful for LoginViewModel's UX path.
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
    }
}
