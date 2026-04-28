package fr.forumhfr.redface2.core.network.cookie

import android.os.Looper
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * Serializes writes to the persisted [CookieStore] so a `saveFromResponse` can never be
     * reordered with a concurrent `clear` (logout) on the way to disk. The runtime cache is
     * already a `StateFlow` with atomic value writes; the mutex covers only the disk path
     * where order matters for crash-survivability of the auth state.
     */
    private val storeMutex = Mutex()

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

    /**
     * OkHttp's CookieJar contract is synchronous, so a request that fires before the cache
     * has been primed from the persisted store would otherwise leak unauthenticated. We
     * block here until the first store emission lands. The block runs on OkHttp's dispatcher
     * thread (where `Call.execute` ultimately resolves), never on the main thread — Hilt
     * builds the @Singleton jar lazily but the actual call sites are coroutines on
     * @IoDispatcher or OkHttp's worker threads, so blocking here is safe even with
     * StrictMode. The window is sub-ms in practice (DataStore<Preferences> file is tiny) and
     * only triggers on the very first authenticated request after cold start.
     */
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val cookies = cache.value ?: run {
            // Defensive: blocking on Main is the headline ANR scenario for this code path.
            // The Looper API throws in JVM-only unit tests (`android.jar` is compile-only),
            // so we guard the read and skip the check when there is no Looper subsystem.
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            check(mainLooper == null || Looper.myLooper() != mainLooper) {
                "PersistentCookieJar.loadForRequest must not block the Main thread. The cookie " +
                    "cache is uninitialized; this call site is wrong (an OkHttp Call should " +
                    "execute off-main, on @IoDispatcher or an OkHttp worker thread)."
            }
            runBlocking { state.filterNotNull().first() }
        }
        return cookies.filter { it.matches(url) && it.expiresAt > now }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val merged = merge(cache.value ?: emptyList(), cookies)
        cache.value = merged
        scope.launch { storeMutex.withLock { store.save(merged) } }
    }

    /**
     * Clears the runtime cache synchronously and the persisted store asynchronously. Used by
     * `AuthRepository.logout()` so the auth state flips to `Anonymous` immediately, without
     * waiting for the DataStore commit. Disk writes are serialized via `storeMutex` so a
     * logout can never be reordered behind a stale `saveFromResponse` on the way to disk.
     */
    fun clear() {
        cache.value = emptyList()
        scope.launch { storeMutex.withLock { store.clear() } }
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
