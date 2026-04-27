package fr.forumhfr.redface2.core.network.cookie

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Production CookieJar bound to the @AuthenticatedClient OkHttp instance. Reads from and
 * writes to a [CookieStore] (DataStore-backed in production), and exposes the runtime cache
 * as a [StateFlow] so consumers like `DefaultAuthRepository` can derive auth state without
 * waiting for the eventually-consistent DataStore round-trip.
 *
 * State semantics:
 * - The flow's value type is nullable: `null` means "the persisted store hasn't been read
 *   yet" (fresh `@Singleton` instance, collector hasn't fired). Consumers that care about
 *   no-false-Anonymous semantics (e.g. `observeAuthState()`) must `filterNotNull` to avoid
 *   emitting `Anonymous` while the persisted session is still loading from disk.
 * - Once the collector picks up the first store emission, the value is a non-null list and
 *   stays non-null for the rest of the singleton's life.
 *
 * Concurrency:
 * - `loadForRequest`/`saveFromResponse` answer synchronously (OkHttp's CookieJar contract).
 * - `saveFromResponse` updates the StateFlow synchronously and launches a coroutine to
 *   persist via [CookieStore.save]. The fire-and-forget is intentional — OkHttp shouldn't
 *   wait for disk I/O.
 * - The collector launched in `init` mirrors store updates back into the cache (e.g. a
 *   logout that clears the store from another component is observed here too).
 */
@Singleton
class PersistentCookieJar @Inject constructor(
    private val store: CookieStore,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : CookieJar {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val cache = MutableStateFlow<List<Cookie>?>(null)

    /**
     * Runtime cookie state. Emits `null` until the persisted store has been read for the
     * first time, then a non-null list updated synchronously on every saveFromResponse /
     * clear and re-emitted whenever the store flow fires.
     */
    val state: StateFlow<List<Cookie>?> = cache.asStateFlow()

    init {
        scope.launch {
            store.observe().collect { cookies -> cache.value = cookies }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return (cache.value ?: emptyList())
            .filter { it.matches(url) && it.expiresAt > now }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val merged = merge(cache.value ?: emptyList(), cookies)
        cache.value = merged
        scope.launch { store.save(merged) }
    }

    /**
     * Clears the runtime cache synchronously and the persisted store asynchronously. Used by
     * `AuthRepository.logout()` so the auth state flips to `Anonymous` immediately, without
     * waiting for the DataStore commit.
     */
    fun clear() {
        cache.value = emptyList()
        scope.launch { store.clear() }
    }

    private fun merge(existing: List<Cookie>, incoming: List<Cookie>): List<Cookie> {
        val now = System.currentTimeMillis()
        val byKey = mutableMapOf<String, Cookie>()
        existing.forEach { cookie -> byKey[keyOf(cookie)] = cookie }
        incoming.forEach { cookie ->
            val key = keyOf(cookie)
            if (cookie.expiresAt < now) byKey.remove(key) else byKey[key] = cookie
        }
        return byKey.values.toList()
    }

    private fun keyOf(cookie: Cookie): String = "${cookie.domain}|${cookie.path}|${cookie.name}"
}
