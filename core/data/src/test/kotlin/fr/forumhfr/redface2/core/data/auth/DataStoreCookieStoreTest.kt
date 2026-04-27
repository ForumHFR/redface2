package fr.forumhfr.redface2.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val mdUser = makeCookie(name = "md_user", value = "xaat", httpOnly = true)
        val mdPass = makeCookie(name = "md_pass", value = "deadbeef", httpOnly = true)
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

    private fun makeCookie(
        name: String,
        value: String,
        domain: String = "forum.hardware.fr",
        path: String = "/",
        expiresAt: Long = System.currentTimeMillis() + 365L * 24 * 3600 * 1000,
        secure: Boolean = true,
        httpOnly: Boolean = false,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(expiresAt)
        .also { if (secure) it.secure() }
        .also { if (httpOnly) it.httpOnly() }
        .build()
}
