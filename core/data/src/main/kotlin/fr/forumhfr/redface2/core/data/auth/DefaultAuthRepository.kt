package fr.forumhfr.redface2.core.data.auth

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.auth.AuthRemoteDataSource
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val remote: AuthRemoteDataSource,
    private val cookieStore: CookieStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    /**
     * The authoritative session state is derived from the persisted cookies. PersistentCookieJar
     * writes incoming Set-Cookie headers through CookieStore, the flow re-emits, and consumers
     * (FlagsScreen footer, navigation guards) react automatically.
     */
    override fun observeAuthState(): Flow<AuthState> = cookieStore.observe().map { cookies ->
        val mdUser = cookies.firstOrNull { it.name == COOKIE_MD_USER && it.value.isNotBlank() }
        if (mdUser != null) AuthState.Authenticated(mdUser.value) else AuthState.Anonymous
    }

    /**
     * The persistence side-effect happens implicitly: AuthRemoteDataSource POSTs through the
     * @AuthenticatedClient OkHttp instance, whose CookieJar (PersistentCookieJar) writes the
     * Set-Cookie headers to CookieStore. The Result we return here is the immediate
     * classification — useful for LoginViewModel's UX path; observeAuthState() is the source
     * of truth for global state.
     */
    override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
        withContext(ioDispatcher) { remote.login(pseudo, password) }

    override suspend fun logout() {
        withContext(ioDispatcher) { cookieStore.clear() }
    }

    private companion object {
        const val COOKIE_MD_USER = "md_user"
    }
}
