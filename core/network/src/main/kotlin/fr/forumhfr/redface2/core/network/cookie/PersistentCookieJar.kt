package fr.forumhfr.redface2.core.network.cookie

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Production CookieJar bound to the @AuthenticatedClient OkHttp instance. Reads from and
 * writes to a [CookieStore] (DataStore-backed in production). Maintains an in-memory snapshot
 * because OkHttp's CookieJar is non-suspending — both methods must answer synchronously.
 *
 * Lifecycle:
 * - On construction, the cache is primed synchronously from the store. This blocking call
 *   happens once at app boot, on the IO dispatcher, against a tiny DataStore<Preferences> file.
 *   It guarantees the very first OkHttp request after app start sees the persisted session
 *   cookies even if it fires before the flow collector has run.
 * - A long-running collector keeps the cache in sync with the store on subsequent emissions
 *   (e.g. a logout in another component).
 * - Each saveFromResponse merges with the current cache, updates the AtomicReference, and
 *   launches a coroutine to persist via [CookieStore.save]. The fire-and-forget is intentional:
 *   OkHttp shouldn't wait for disk I/O to acknowledge a response.
 */
@Singleton
class PersistentCookieJar @Inject constructor(
    private val store: CookieStore,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : CookieJar {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val cache = AtomicReference<List<Cookie>>(emptyList())

    init {
        cache.set(runBlocking(dispatcher) { store.observe().first() })
        scope.launch {
            store.observe().collect { cookies -> cache.set(cookies) }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cache.get().filter { it.matches(url) && it.expiresAt > now }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val merged = merge(cache.get(), cookies)
        cache.set(merged)
        scope.launch { store.save(merged) }
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
