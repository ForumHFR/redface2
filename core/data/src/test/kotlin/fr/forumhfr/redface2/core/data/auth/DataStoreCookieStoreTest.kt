package fr.forumhfr.redface2.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DataStoreCookieStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreCookieStore

    @Before
    fun setUp() {
        val file = File(tempFolder.newFolder(), "cookies.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        store = DataStoreCookieStore(dataStore)
    }

    @After
    fun tearDown() {
        // PreferenceDataStoreFactory caches active stores by file path. Releasing the
        // reference is enough between tests because TemporaryFolder gives each test a
        // fresh folder, so file paths never collide.
    }

    @Test
    fun `empty store observes empty list`() = runTest(UnconfinedTestDispatcher()) {
        store.observe().test {
            assertEquals(emptyList<Cookie>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save then observe returns saved cookies`() = runTest(UnconfinedTestDispatcher()) {
        val cookie = makeCookie(name = "md_user", value = "xaat")
        store.save(listOf(cookie))

        store.observe().test {
            val cookies = awaitItem()
            assertEquals(1, cookies.size)
            assertEquals("md_user", cookies[0].name)
            assertEquals("xaat", cookies[0].value)
            assertEquals("forum.hardware.fr", cookies[0].domain)
            assertEquals("/", cookies[0].path)
            assertTrue(cookies[0].secure)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `expired cookies are filtered out at read time`() = runTest(UnconfinedTestDispatcher()) {
        val now = System.currentTimeMillis()
        val expired = makeCookie(name = "old", value = "x", expiresAt = now - 1_000)
        val active = makeCookie(name = "new", value = "y", expiresAt = now + 60_000)
        store.save(listOf(expired, active))

        store.observe().test {
            val cookies = awaitItem()
            assertEquals(1, cookies.size)
            assertEquals("new", cookies[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear removes all cookies`() = runTest(UnconfinedTestDispatcher()) {
        store.save(listOf(makeCookie(name = "md_user", value = "xaat")))
        store.clear()

        store.observe().test {
            assertEquals(emptyList<Cookie>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple cookies are persisted with their metadata`() = runTest(UnconfinedTestDispatcher()) {
        // Build cookies inline to keep the httpOnly assertion explicit — the makeCookie helper
        // is intentionally narrow (5 params) so adding a flag here doesn't bloat its signature.
        val mdUser = Cookie.Builder()
            .name("md_user")
            .value("xaat")
            .domain("forum.hardware.fr")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
            .secure()
            .httpOnly()
            .build()
        val mdPass = Cookie.Builder()
            .name("md_pass")
            .value("deadbeef")
            .domain("forum.hardware.fr")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
            .secure()
            .httpOnly()
            .build()
        store.save(listOf(mdUser, mdPass))

        store.observe().test {
            val cookies = awaitItem().sortedBy { it.name }
            assertEquals(2, cookies.size)
            assertEquals("md_pass", cookies[0].name)
            assertEquals("md_user", cookies[1].name)
            assertTrue(cookies.all { it.httpOnly })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt persisted payload fails closed to empty cookies`() = runTest(UnconfinedTestDispatcher()) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(DataStoreCookieStore.KEY_SESSION_COOKIES)] = "not-json"
        }

        store.observe().test {
            assertEquals(emptyList<Cookie>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt persisted payload fails closed but observe recovers after valid save`() =
        runTest(UnconfinedTestDispatcher()) {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(DataStoreCookieStore.KEY_SESSION_COOKIES)] = "not-json"
            }

            store.observe().test {
                assertEquals(emptyList<Cookie>(), awaitItem())

                store.save(listOf(makeCookie(name = "md_user", value = "xaat")))

                val cookies = awaitItem()
                assertEquals(1, cookies.size)
                assertEquals("md_user", cookies.single().name)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Pins the on-disk JSON shape of the cookie list. Every build that wrote cookies
     * to DataStore produced this exact key set ; a future Kotlin-side rename of any
     * `CookieDto` property would silently log out every existing user (the
     * `runCatching` upstream catches the resulting `MissingFieldException` and emits
     * an empty list, which the UI reads as "session expired"). The frozen
     * `@SerialName` annotations on `CookieDto` keep the JSON keys stable ; this test
     * proves a payload written by a hypothetical past build (or hand-crafted by a
     * user manipulating the file) still decodes to a usable cookie.
     */
    @Test
    fun `legacy persisted payload with the historical key set still decodes`() =
        runTest(UnconfinedTestDispatcher()) {
            val farFuture = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
            val historicalJson = """
                [
                  {
                    "name": "md_user",
                    "value": "xaat",
                    "domain": "forum.hardware.fr",
                    "path": "/",
                    "expiresAt": $farFuture,
                    "secure": true,
                    "httpOnly": true,
                    "hostOnly": true
                  }
                ]
            """.trimIndent()
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(DataStoreCookieStore.KEY_SESSION_COOKIES)] = historicalJson
            }

            store.observe().test {
                val cookies = awaitItem()
                assertEquals(1, cookies.size)
                assertEquals("md_user", cookies.single().name)
                assertEquals("xaat", cookies.single().value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `save writes the full historical key set even for default-valued fields`() =
        runTest(UnconfinedTestDispatcher()) {
            store.save(listOf(makeCookie(name = "md_user", value = "xaat")))

            val raw = dataStore.data.first()[stringPreferencesKey(DataStoreCookieStore.KEY_SESSION_COOKIES)]
                .orEmpty()

            assertTrue(raw.contains("\"name\""))
            assertTrue(raw.contains("\"value\""))
            assertTrue(raw.contains("\"domain\""))
            assertTrue(raw.contains("\"path\""))
            assertTrue(raw.contains("\"expiresAt\""))
            assertTrue(raw.contains("\"secure\""))
            assertTrue(raw.contains("\"httpOnly\""))
            assertTrue(raw.contains("\"hostOnly\""))
        }

    /**
     * If a future schema adds a new field to `CookieDto`, an older payload missing
     * that field must still decode (defaults take over). Without defaults, kotlinx
     * throws `MissingFieldException` and the whole list is wiped — silent logout.
     */
    @Test
    fun `payload missing a future-only optional field still decodes via defaults`() =
        runTest(UnconfinedTestDispatcher()) {
            // Simulate an older build that wrote 6 fields out of the 8 the current
            // schema knows. The two missing ones (httpOnly, hostOnly) must default
            // to false rather than blowing up the list.
            val farFuture = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
            val truncatedJson = """
                [
                  {
                    "name": "md_user",
                    "value": "xaat",
                    "domain": "forum.hardware.fr",
                    "path": "/",
                    "expiresAt": $farFuture,
                    "secure": true
                  }
                ]
            """.trimIndent()
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(DataStoreCookieStore.KEY_SESSION_COOKIES)] = truncatedJson
            }

            store.observe().test {
                val cookies = awaitItem()
                assertEquals(
                    "missing fields must not wipe the cookie list",
                    1,
                    cookies.size,
                )
                assertEquals("md_user", cookies.single().name)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun makeCookie(
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
}
