package fr.forumhfr.redface2.core.network.cookie

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory cookie jar kept for tests after Phase 1B replaced the production binding with
 * [PersistentCookieJar]. No longer part of the Hilt graph — instantiate directly when an
 * isolated CookieJar is needed. Cookies are keyed by host so that requests to
 * forum.hardware.fr always replay the same session, regardless of the URL path.
 */
class InMemoryCookieJar : CookieJar {
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
