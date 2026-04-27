package fr.forumhfr.redface2.core.network.cookie

import app.cash.turbine.test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `clear empties the cache synchronously and clears the store`() = runTest {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher(testScheduler))

        jar.clear()

        assertEquals(emptyList<Cookie>(), jar.state.value)
        assertEquals(emptyList<Cookie>(), store.lastSaved)
        assertTrue(store.cleared)
    }

    @Test
    fun `state exposes a non-blocking StateFlow that mirrors the store`() = runTest {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher(testScheduler))

        jar.state.test {
            // Initial value is the persisted state — already populated synchronously through
            // the unconfined collector, so the very first emission is a populated list, not
            // the null sentinel.
            val first = awaitItem()
            assertEquals(1, first?.size)
            assertEquals("md_user", first!![0].name)

            jar.saveFromResponse(baseUrl, listOf(cookie("md_pass", "deadbeef")))
            val second = awaitItem()
            assertEquals(2, second?.size)

            jar.clear()
            assertEquals(emptyList<Cookie>(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state stays null until the store collector emits for the first time`() {
        // A FakeCookieStore that suspends forever lets us observe the null sentinel without
        // racing the unconfined dispatcher.
        val store = NeverEmittingCookieStore()
        val jar = PersistentCookieJar(store, UnconfinedTestDispatcher())

        assertNull(
            "state must be null while the store has not emitted yet — consumers depend on this " +
                "to avoid a false Anonymous emission at cold start",
            jar.state.value,
        )
    }

    @Test
    fun `loadForRequest before first store emission blocks until cookies arrive`() {
        // Simulate the dangerous cold-start path: an authenticated OkHttp call fires after
        // app start but before the DataStore collector has fed the persisted cookies into
        // the jar. Returning emptyList() here would leak the request unauthenticated; the
        // jar must instead suspend until the first emission lands.
        //
        // We drive this with a real coroutine dispatcher (Dispatchers.IO) — UnconfinedTestDispatcher
        // would short-circuit the runBlocking inside loadForRequest because the test scheduler
        // would never advance past the inner suspend. Real threads are the simplest model here
        // and match the production call site (OkHttp's worker threads).
        val store = ManuallyControlledCookieStore()
        val jar = PersistentCookieJar(store, Dispatchers.IO)

        assertNull(
            "precondition: state must be null while the controlled store has not emitted yet",
            jar.state.value,
        )

        val executor = Executors.newSingleThreadExecutor()
        val cookieFuture: CompletableFuture<List<Cookie>> = CompletableFuture.supplyAsync(
            { jar.loadForRequest(baseUrl) },
            executor,
        )

        // Give the supplier a moment to enter loadForRequest and start blocking on the flow.
        Thread.sleep(LOAD_BLOCKING_GRACE_MS)
        assertFalse(
            "loadForRequest should still be blocking while the cookie cache is uninitialized",
            cookieFuture.isDone,
        )

        // Releasing the cookie store unblocks the jar's collector, which sets cache.value
        // and resumes the runBlocking inside loadForRequest.
        runBlocking {
            store.emit(listOf(cookie("md_user", "xaat")))
        }

        val loaded = cookieFuture.get(LOAD_BLOCKING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(loaded)
        assertEquals(1, loaded.size)
        assertEquals("md_user", loaded[0].name)

        executor.shutdownNow()
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
        var cleared: Boolean = false
            private set

        override fun observe(): Flow<List<Cookie>> = state.asStateFlow()

        override suspend fun save(cookies: List<Cookie>) {
            lastSaved = cookies
            saveCount += 1
            state.value = cookies
        }

        override suspend fun clear() {
            cleared = true
            lastSaved = emptyList()
            state.value = emptyList()
        }
    }

    private class NeverEmittingCookieStore : CookieStore {
        override fun observe(): Flow<List<Cookie>> = kotlinx.coroutines.flow.flow {
            // Suspend forever — the cookie jar's collector waits without ever updating cache.
            kotlinx.coroutines.suspendCancellableCoroutine<Nothing> { /* never resumes */ }
        }

        override suspend fun save(cookies: List<Cookie>) = Unit
        override suspend fun clear() = Unit
    }

    private class ManuallyControlledCookieStore : CookieStore {
        private val flow = MutableSharedFlow<List<Cookie>>(replay = 0)

        suspend fun emit(cookies: List<Cookie>) = flow.emit(cookies)

        override fun observe(): Flow<List<Cookie>> = flow

        override suspend fun save(cookies: List<Cookie>) {
            flow.emit(cookies)
        }

        override suspend fun clear() {
            flow.emit(emptyList())
        }
    }

    private companion object {
        const val LOAD_BLOCKING_GRACE_MS = 100L
        const val LOAD_BLOCKING_TIMEOUT_MS = 2_000L
    }
}
