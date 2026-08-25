package fr.forumhfr.redface2.core.data.messages

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCacheException
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePrivateMessageContentCacheTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: FailingWriteDataStore
    private lateinit var sessionCache: PrivateMessageThreadSessionCache
    private lateinit var diskCache: PrivateMessageThreadDiskCache
    private lateinit var cache: DataStorePrivateMessageContentCache

    @Before
    fun setUp() {
        dataStore = FailingWriteDataStore(
            PreferenceDataStoreFactory.create(
                produceFile = { tempFolder.newFile("mp-cache.preferences_pb") },
            ),
        )
        sessionCache = PrivateMessageThreadSessionCache()
        diskCache = mockk(relaxed = true)
        cache = DataStorePrivateMessageContentCache(dataStore, sessionCache, diskCache)
    }

    @Test
    fun `default OFF startup scrubs once while normal OFF access never reaches content rows`() = runTest {
        assertFalse(cache.isEnabled())

        cache.reconcileOnStartup()
        cache.setEnabled(false)
        assertNull(cache.readIfEnabled("alice", 42, 1))
        cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) { true }

        coVerify(exactly = 1) { diskCache.clearAll() }
        coVerify(exactly = 0) { diskCache.clearForUser(any()) }
        coVerify(exactly = 0) { diskCache.read(any(), any(), any()) }
        coVerify(exactly = 0) { diskCache.replace(any(), any(), any()) }
    }

    @Test
    fun `disabling persists OFF advances RAM generation and clears every account`() = runTest {
        cache.setEnabled(true)
        val oldStamp = sessionCache.capture("alice")
        sessionCache.write(oldStamp, 42, 1, thread())
        coEvery { diskCache.clearAll() } coAnswers {
            assertFalse("OFF must be effective before Room DELETE", cache.isEnabled())
            assertFalse("RAM generation must advance before Room DELETE", sessionCache.isCurrent(oldStamp))
        }

        cache.setEnabled(false)

        assertFalse(cache.isEnabled())
        assertFalse(sessionCache.isCurrent(oldStamp))
        coVerify(exactly = 1) { diskCache.clearAll() }
    }

    @Test
    fun `failed OFF purge stays pending and is retried at startup`() = runTest {
        cache.setEnabled(true)
        coEvery { diskCache.clearAll() } throws IllegalStateException("unavailable") andThen Unit

        val failure = runCatching { cache.setEnabled(false) }.exceptionOrNull()

        assertTrue(failure is PrivateMessageContentCacheException.PurgeFailed)
        assertFalse(cache.isEnabled())
        assertTrue(cache.observePurgePending().first())
        cache.reconcileOnStartup()
        coVerify(exactly = 2) { diskCache.clearAll() }
    }

    @Test
    fun `logout purges while OFF and an enabled failure is retried globally`() = runTest {
        cache.purgeForUser("alice")
        coVerify(exactly = 1) { diskCache.clearForUser("alice") }

        cache.setEnabled(true)
        coEvery { diskCache.clearForUser("alice") } throws IllegalStateException("unavailable")
        assertTrue(runCatching { cache.purgeForUser("alice") }.isFailure)

        assertFalse(cache.isEnabled())
        cache.reconcileOnStartup()
        coVerify(exactly = 1) { diskCache.clearAll() }
    }

    @Test
    fun `OFF waits for an admitted transaction then purges and rejects every later write`() = runTest {
        cache.setEnabled(true)
        val finalSealReached = CompletableDeferred<Unit>()
        val admitTransaction = CompletableDeferred<Unit>()

        val admittedWrite = async {
            cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) {
                finalSealReached.complete(Unit)
                admitTransaction.await()
                true
            }
        }
        finalSealReached.await()
        val disable = async { cache.setEnabled(false) }
        val persistedOff = cache.observeEnabled().first { enabled -> !enabled }

        assertFalse(persistedOff)
        assertFalse(disable.isCompleted)
        coVerify(exactly = 0) { diskCache.replace(any(), any(), any()) }
        coVerify(exactly = 0) { diskCache.clearAll() }

        admitTransaction.complete(Unit)
        admittedWrite.await()
        disable.await()
        cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) { true }

        coVerifyOrder {
            diskCache.replace("alice", any(), any())
            diskCache.clearAll()
        }
        coVerify(exactly = 1) { diskCache.replace(any(), any(), any()) }
    }

    @Test
    fun `account purge follows an admitted transaction and a stale seal cannot resurrect it`() = runTest {
        cache.setEnabled(true)
        val replaceStarted = CompletableDeferred<Unit>()
        val finishReplace = CompletableDeferred<Unit>()
        coEvery { diskCache.replace(any(), any(), any()) } coAnswers {
            replaceStarted.complete(Unit)
            finishReplace.await()
        }

        val admittedWrite = async {
            cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) { true }
        }
        replaceStarted.await()
        val purge = async { cache.purgeForUser("alice") }
        runCurrent()
        assertFalse(purge.isCompleted)

        finishReplace.complete(Unit)
        admittedWrite.await()
        purge.await()
        cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) { false }

        coVerifyOrder {
            diskCache.replace("alice", any(), any())
            diskCache.clearForUser("alice")
        }
        coVerify(exactly = 1) { diskCache.replace(any(), any(), any()) }
    }

    @Test
    fun `pending startup purge blocks access until reconciliation finishes`() = runTest {
        cache.setEnabled(true)
        coEvery { diskCache.clearForUser("alice") } throws IllegalStateException("unavailable")
        assertTrue(runCatching { cache.purgeForUser("alice") }.isFailure)
        val purgeStarted = CompletableDeferred<Unit>()
        val finishPurge = CompletableDeferred<Unit>()
        coEvery { diskCache.clearAll() } coAnswers {
            purgeStarted.complete(Unit)
            finishPurge.await()
        }
        coEvery { diskCache.read("alice", 42, 1) } returns null

        val reconciliation = async { cache.reconcileOnStartup() }
        purgeStarted.await()
        val read = async { cache.readIfEnabled("alice", 42, 1) }
        runCurrent()

        assertFalse(cache.isEnabled())
        assertFalse(read.isCompleted)
        coVerify(exactly = 0) { diskCache.read(any(), any(), any()) }

        finishPurge.complete(Unit)
        reconciliation.await()
        read.await()
        coVerify(exactly = 1) { diskCache.read("alice", 42, 1) }
    }

    @Test
    fun `failed OFF preference write remains ON and neither invalidates nor purges`() = runTest {
        cache.setEnabled(true)
        val stamp = sessionCache.capture("alice")
        dataStore.failNextWrite = true

        val failure = runCatching { cache.setEnabled(false) }.exceptionOrNull()

        assertTrue(failure is PrivateMessageContentCacheException.PreferenceWriteFailed)
        assertTrue(cache.isEnabled())
        assertTrue(sessionCache.isCurrent(stamp))
        coVerify(exactly = 0) { diskCache.clearAll() }
    }

    @Test
    fun `manual retry keeps access blocked until the failed purge succeeds`() = runTest {
        cache.setEnabled(true)
        coEvery { diskCache.clearAll() } throws IOException("busy") andThen Unit
        assertTrue(runCatching { cache.setEnabled(false) }.isFailure)

        cache.retryPendingPurge()

        assertFalse(cache.isEnabled())
        assertFalse(cache.observePurgePending().first())
        coVerify(exactly = 2) { diskCache.clearAll() }
    }

    private class FailingWriteDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failNextWrite: Boolean = false

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("preference unavailable")
            }
            return delegate.updateData(transform)
        }
    }

    private fun thread() = PrivateMessageThread(
        threadId = 42,
        subject = "subject",
        correspondent = "correspondent",
        messages = emptyList(),
        page = 1,
        totalPages = 1,
    )
}
