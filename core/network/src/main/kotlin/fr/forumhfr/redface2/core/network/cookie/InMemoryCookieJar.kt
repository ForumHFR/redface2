package fr.forumhfr.redface2.core.network.cookie

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory cookie jar used during Phase 1A. A persistent Keystore-backed jar will replace
 * this implementation in Phase 1B (cf. ADR-002 — DataStore + Android Keystore, no plaintext
 * password). Cookies are keyed by host so that requests to forum.hardware.fr always replay
 * the same session, regardless of the URL path.
 */
@Singleton
class InMemoryCookieJar @Inject constructor() : CookieJar {
    private val store: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val bucket = store.getOrPut(url.host) { mutableListOf() }
        synchronized(bucket) {
            cookies.forEach { incoming ->
                bucket.removeAll { existing -> existing.name == incoming.name }
                if (!incoming.hasExpired()) bucket += incoming
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val bucket = store[url.host] ?: return emptyList()
        synchronized(bucket) {
            bucket.removeAll { it.hasExpired() }
            return bucket.filter { it.matches(url) }.toList()
        }
    }

    fun clear() {
        store.clear()
    }

    private fun Cookie.hasExpired(): Boolean = expiresAt < System.currentTimeMillis()
}
