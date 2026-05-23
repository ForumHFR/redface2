package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val repository = FakeUserPreferencesRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init hydrates proxy fields from preferences`() = runTest {
        repository.emit(
            ProxyConfig(
                enabled = true,
                host = "proxy.local",
                port = 8_080,
                username = "user",
                password = "secret",
            ),
        )

        val viewModel = SettingsViewModel(repository)
        val state = viewModel.state.value

        assertTrue(state.proxyEnabled)
        assertEquals("proxy.local", state.proxyHost)
        assertEquals("8080", state.proxyPort)
        assertEquals("user", state.proxyUsername)
        assertEquals("secret", state.proxyPassword)
    }

    @Test
    fun `save rejects enabled proxy with missing host or invalid port`() = runTest {
        val viewModel = SettingsViewModel(repository)

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged(""))
        viewModel.submit(SettingsIntent.ProxyPortChanged("70000"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        assertEquals(SettingsError.InvalidProxy, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun `save persists normalized proxy config`() = runTest {
        val viewModel = SettingsViewModel(repository)

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged(" proxy.local "))
        viewModel.submit(SettingsIntent.ProxyPortChanged("8080abc"))
        viewModel.submit(SettingsIntent.ProxyUsernameChanged(" user "))
        viewModel.submit(SettingsIntent.ProxyPasswordChanged("secret"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        val saved = requireNotNull(repository.lastSaved)
        assertTrue(saved.enabled)
        assertEquals("proxy.local", saved.host)
        assertEquals(8_080, saved.port)
        assertEquals("user", saved.username)
        assertEquals("secret", saved.password)
        assertTrue(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `save reports persist failure and re-enables saving when repository throws`() = runTest {
        repository.failOnSave = true
        val viewModel = SettingsViewModel(repository)

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged("proxy.local"))
        viewModel.submit(SettingsIntent.ProxyPortChanged("8080"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        val state = viewModel.state.value
        assertEquals(SettingsError.PersistFailed, state.error)
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertTrue(state.canSave)
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        private val config = MutableStateFlow(ProxyConfig())
        var saveCalls: Int = 0
            private set
        var lastSaved: ProxyConfig? = null
            private set
        var failOnSave: Boolean = false

        override fun observeProxyConfig(): Flow<ProxyConfig> = config

        override suspend fun saveProxyConfig(config: ProxyConfig) {
            saveCalls += 1
            check(!failOnSave) { "boom" }
            val normalized = config.normalized()
            lastSaved = normalized
            this.config.value = normalized
        }

        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = config.value

        fun emit(value: ProxyConfig) {
            config.value = value
        }
    }
}
