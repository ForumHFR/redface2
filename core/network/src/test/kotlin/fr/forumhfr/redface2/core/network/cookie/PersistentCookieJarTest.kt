package fr.forumhfr.redface2.core.network.cookie

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentCookieJarTest {

    private val baseUrl: HttpUrl = "https://forum.hardware.fr/forum2.php".toHttpUrl()

    @Test
    fun `loadForRequest returns persisted cookies whose domain and path match`() {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        val loaded = jar.loadForRequest(baseUrl)

        assertEquals(1, loaded.size)
        assertEquals("md_user", loaded[0].name)
    }

    @Test
    fun `loadForRequest filters out cookies with mismatching host`() {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat", domain = "example.org")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        val loaded = jar.loadForRequest(baseUrl)

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `loadForRequest filters out expired cookies even if the store leaks them`() {
        val expired = cookie("md_user", "xaat", expiresAt = System.currentTimeMillis() - 1_000)
        val store = FakeCookieStore(initial = listOf(expired))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        val loaded = jar.loadForRequest(baseUrl)

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `saveFromResponse merges with existing cache and persists to the store`() {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        jar.saveFromResponse(baseUrl, listOf(cookie("md_pass", "deadbeef")))

        val loaded = jar.loadForRequest(baseUrl).sortedBy { it.name }
        assertEquals(2, loaded.size)
        assertEquals("md_pass", loaded[0].name)
        assertEquals("md_user", loaded[1].name)

        val saved = store.lastSaved.sortedBy { it.name }
        assertEquals(2, saved.size)
        assertEquals("md_pass", saved[0].name)
        assertEquals("md_user", saved[1].name)
    }

    @Test
    fun `saveFromResponse with deletion-marker cookie removes the matching cookie`() {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        // HFR / RFC 6265 sends an "expire now" cookie to delete a session.
        jar.saveFromResponse(baseUrl, listOf(cookie("md_user", "", expiresAt = 0L)))

        val loaded = jar.loadForRequest(baseUrl)
        assertTrue(loaded.isEmpty())
        assertTrue(store.lastSaved.none { it.name == "md_user" })
    }

    @Test
    fun `saveFromResponse with empty list is a no-op and does not touch the store`() {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())
        val initialSaveCount = store.saveCount

        jar.saveFromResponse(baseUrl, emptyList())

        assertEquals("save() should not have been triggered for an empty incoming cookie list",
            initialSaveCount, store.saveCount)
        assertFalse(jar.loadForRequest(baseUrl).isEmpty())
    }

    private fun cookie(
        name: String,
        value: String,
        domain: String = "forum.hardware.fr",
        path: String = "/",
        expiresAt: Long = System.currentTimeMillis() + 365L * 24 * 3600 * 1000,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(expiresAt)
        .secure()
        .build()

    private class FakeCookieStore(initial: List<Cookie>) : CookieStore {
        private val state = MutableStateFlow(initial)
        var lastSaved: List<Cookie> = initial
            private set
        var saveCount: Int = 0
            private set

        override fun observe(): Flow<List<Cookie>> = state.asStateFlow()

        override suspend fun save(cookies: List<Cookie>) {
            lastSaved = cookies
            saveCount += 1
            state.value = cookies
        }

        override suspend fun clear() {
            lastSaved = emptyList()
            state.value = emptyList()
        }
    }
}
