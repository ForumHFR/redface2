package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val repository = FakeUserPreferencesRepository()
    private val topicCacheMaintenance = FakeTopicCacheMaintenance()

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

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertTrue(state.proxyEnabled)
        assertEquals("proxy.local", state.proxyHost)
        assertEquals("8080", state.proxyPort)
        assertEquals("user", state.proxyUsername)
        assertEquals("secret", state.proxyPassword)
    }

    @Test
    fun `save rejects enabled proxy with missing host or invalid port`() = runTest {
        val viewModel = newViewModel()

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
        val viewModel = newViewModel()

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
        val viewModel = newViewModel()

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

    // ──────────────────────────────────────────────────────────────────────
    // Topic cache maintenance — "Vider le cache des topics" action
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ClearTopicCacheClicked opens the confirmation dialog without touching the cache`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        assertTrue(viewModel.state.value.showClearTopicCacheConfirm)
        assertEquals(
            "clear() must NOT run until the user confirms",
            0,
            topicCacheMaintenance.clearCalls,
        )
    }

    @Test
    fun `ClearTopicCacheDismissed closes the dialog without calling clear`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheDismissed)

        assertFalse(viewModel.state.value.showClearTopicCacheConfirm)
        assertEquals(0, topicCacheMaintenance.clearCalls)
        assertNull(viewModel.state.value.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed runs clear and surfaces Success`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, topicCacheMaintenance.clearCalls)
        assertFalse("dialog must close at confirm time", state.showClearTopicCacheConfirm)
        assertFalse("isClearing must flip back to false after success", state.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Success, state.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed exposes in-progress state while clear is running`() = runTest {
        topicCacheMaintenance.blockUntil = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        assertTrue(viewModel.state.value.isClearingTopicCache)
        assertFalse(viewModel.state.value.canClearTopicCache)

        topicCacheMaintenance.blockUntil?.complete(Unit)
        yield()

        assertFalse(viewModel.state.value.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Success, viewModel.state.value.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed surfaces Failure when the maintenance call throws`() = runTest {
        topicCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, topicCacheMaintenance.clearCalls)
        assertFalse(state.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Failure, state.topicCacheClearResult)
        // Proxy state stays untouched — the two domains must not bleed.
        assertNull(state.error)
        assertFalse(state.saved)
    }

    @Test
    fun `re-clicking after a previous result resets the inline message`() = runTest {
        topicCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)
        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)
        assertEquals(TopicCacheClearResult.Failure, viewModel.state.value.topicCacheClearResult)

        // Second pass — user retries
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        // The previous result must be cleared when the new confirmation opens, so the
        // dialog isn't surfaced over a stale "échec" message that no longer reflects the
        // pending operation.
        assertTrue(viewModel.state.value.showClearTopicCacheConfirm)
        assertNull(viewModel.state.value.topicCacheClearResult)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Ignore topic cache — alpha toggle
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates ignoreTopicCache from the persisted preference`() = runTest {
        repository.emitIgnoreTopicCache(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.ignoreTopicCache)
        assertFalse(viewModel.state.value.ignoreTopicCacheError)
    }

    @Test
    fun `IgnoreTopicCacheChanged true persists the new value and exposes it`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        assertTrue(state.ignoreTopicCache)
        assertFalse(state.isUpdatingIgnoreTopicCache)
        assertFalse(state.ignoreTopicCacheError)
        assertEquals(1, repository.ignoreTopicCacheSetCalls)
        assertEquals(true, repository.lastIgnoreTopicCacheSet)
    }

    @Test
    fun `IgnoreTopicCacheChanged failure reverts the optimistic flip and raises the error flag`() = runTest {
        repository.failOnIgnoreTopicCacheSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        assertFalse("optimistic flip must revert on failure", state.ignoreTopicCache)
        assertFalse(state.isUpdatingIgnoreTopicCache)
        assertTrue("a topic-cache-specific error flag must be raised", state.ignoreTopicCacheError)
        // Pin the contract: the DataStore write must actually be attempted before the failure
        // is surfaced. Without this assertion a future refactor that short-circuits the call
        // (e.g. early-return on same-value) would still pass since no exception is raised.
        assertEquals(
            "DataStore write must have been attempted before the failure was surfaced",
            1,
            repository.ignoreTopicCacheSetCalls,
        )
        // The proxy-scoped error remains untouched — the two domains must not bleed.
        assertNull("proxy SettingsError must not be set by an ignore-topic-cache failure", state.error)
        assertFalse(state.saved)
    }

    @Test
    fun `IgnoreTopicCacheChanged exposes in-progress state while DataStore is writing`() = runTest {
        // Block the fake's setIgnoreTopicCache until we explicitly release it, so we can observe
        // the intermediate state. Without this gate `runTest` would drain the launch in one shot
        // and we'd only see the final post-write state.
        val gate = CompletableDeferred<Unit>()
        repository.blockIgnoreTopicCacheSetUntil = gate
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        // Optimistic flip applied + gate raised before the launch suspends on `await()`.
        val midFlightState = viewModel.state.value
        assertTrue(
            "optimistic value must be exposed before DataStore confirms",
            midFlightState.ignoreTopicCache,
        )
        assertTrue(
            "gate flag must keep the Switch disabled while the write is in flight",
            midFlightState.isUpdatingIgnoreTopicCache,
        )
        assertFalse(
            "canToggleIgnoreTopicCache must be false while the write is in flight",
            midFlightState.canToggleIgnoreTopicCache,
        )

        gate.complete(Unit)

        val finalState = viewModel.state.value
        assertFalse("gate must release after the write completes", finalState.isUpdatingIgnoreTopicCache)
        assertTrue(finalState.ignoreTopicCache)
        assertEquals(1, repository.ignoreTopicCacheSetCalls)
    }

    @Test
    fun `IgnoreTopicCacheChanged does not touch proxy or clear-cache state`() = runTest {
        repository.emit(
            ProxyConfig(enabled = true, host = "proxy.local", port = 8_080, username = "user", password = "secret"),
        )
        val viewModel = newViewModel()
        // Trigger a previous clear-cache result to make sure the toggle does not wipe it.
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)
        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)
        assertEquals(TopicCacheClearResult.Success, viewModel.state.value.topicCacheClearResult)

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        // Proxy state untouched.
        assertTrue(state.proxyEnabled)
        assertEquals("proxy.local", state.proxyHost)
        assertEquals("8080", state.proxyPort)
        // Clear-cache result preserved — toggling one alpha tool must not erase the feedback
        // from the other (orthogonal domains).
        assertEquals(TopicCacheClearResult.Success, state.topicCacheClearResult)
    }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(repository, topicCacheMaintenance)

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        private val config = MutableStateFlow(ProxyConfig())
        private val ignoreTopicCache = MutableStateFlow(false)
        var saveCalls: Int = 0
            private set
        var lastSaved: ProxyConfig? = null
            private set
        var failOnSave: Boolean = false
        var ignoreTopicCacheSetCalls: Int = 0
            private set
        var lastIgnoreTopicCacheSet: Boolean? = null
            private set
        var failOnIgnoreTopicCacheSet: Boolean = false
        var blockIgnoreTopicCacheSetUntil: CompletableDeferred<Unit>? = null

        override fun observeProxyConfig(): Flow<ProxyConfig> = config

        override suspend fun saveProxyConfig(config: ProxyConfig) {
            saveCalls += 1
            check(!failOnSave) { "boom" }
            val normalized = config.normalized()
            lastSaved = normalized
            this.config.value = normalized
        }

        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = config.value

        override fun observeIgnoreTopicCache(): Flow<Boolean> = ignoreTopicCache

        override suspend fun setIgnoreTopicCache(enabled: Boolean) {
            ignoreTopicCacheSetCalls += 1
            // Suspend the write here so the test can observe `isUpdatingIgnoreTopicCache = true`
            // before the launch resumes. Same pattern as `FakeTopicCacheMaintenance.blockUntil`.
            blockIgnoreTopicCacheSetUntil?.await()
            check(!failOnIgnoreTopicCacheSet) { "boom" }
            lastIgnoreTopicCacheSet = enabled
            ignoreTopicCache.value = enabled
        }

        fun emit(value: ProxyConfig) {
            config.value = value
        }

        fun emitIgnoreTopicCache(value: Boolean) {
            ignoreTopicCache.value = value
        }
    }

    private class FakeTopicCacheMaintenance : TopicCacheMaintenance {
        var clearCalls: Int = 0
            private set
        var failOnClear: Boolean = false
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun clearTopicCache() {
            clearCalls += 1
            blockUntil?.await()
            check(!failOnClear) { "boom" }
        }
    }
}
