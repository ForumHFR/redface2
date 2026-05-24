package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreUserPreferencesRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreUserPreferencesRepository
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("user.preferences_pb") },
        )
        repository = DataStoreUserPreferencesRepository(
            dataStore = dataStore,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `empty store observes disabled proxy`() = runTest(dispatcher) {
        repository.observeProxyConfig().test {
            val config = awaitItem()
            assertFalse(config.enabled)
            assertFalse(config.isUsable)
            assertEquals("", config.host)
            assertEquals(null, config.port)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save trims host and username then persists usable proxy`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = true,
                host = " proxy.local ",
                port = 8_080,
                username = " user ",
                password = "secret",
            ),
        )

        repository.observeProxyConfig().test {
            val config = awaitItem()
            assertTrue(config.enabled)
            assertTrue(config.isUsable)
            assertTrue(config.hasCredentials)
            assertEquals("proxy.local", config.host)
            assertEquals(8_080, config.port)
            assertEquals("user", config.username)
            assertEquals("secret", config.password)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `readProxyConfigForNetworkBootstrap returns persisted proxy synchronously`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = true,
                host = "proxy.local",
                port = 3_128,
            ),
        )

        val config = repository.readProxyConfigForNetworkBootstrap()

        assertTrue(config.isUsable)
        assertEquals("proxy.local", config.host)
        assertEquals(3_128, config.port)
    }

    @Test
    fun `saving disabled proxy removes optional fields from effective config`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = false,
                host = " ",
                port = null,
                username = " ",
                password = "",
            ),
        )

        val config = repository.readProxyConfigForNetworkBootstrap()

        assertFalse(config.enabled)
        assertFalse(config.isUsable)
        assertEquals("", config.host)
        assertEquals(null, config.port)
        assertEquals(null, config.username)
        assertEquals(null, config.password)
    }
}
