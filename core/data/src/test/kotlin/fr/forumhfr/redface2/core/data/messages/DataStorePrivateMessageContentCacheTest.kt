package fr.forumhfr.redface2.core.data.messages

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
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

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var sessionCache: PrivateMessageThreadSessionCache
    private lateinit var diskCache: PrivateMessageThreadDiskCache
    private lateinit var cache: DataStorePrivateMessageContentCache

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("mp-cache.preferences_pb") },
        )
        sessionCache = PrivateMessageThreadSessionCache()
        diskCache = mockk(relaxed = true)
        cache = DataStorePrivateMessageContentCache(dataStore, sessionCache, diskCache)
    }

    @Test
    fun `default OFF and startup without pending purge never access private tables`() = runTest {
        assertFalse(cache.isEnabled())

        cache.reconcilePendingPurge()
        cache.setEnabled(false)
        assertNull(cache.readIfEnabled("alice", 42, 1))
        cache.replaceIfEnabled("alice", thread(), java.time.Instant.EPOCH) { true }

        coVerify(exactly = 0) { diskCache.clearAll() }
        coVerify(exactly = 0) { diskCache.clearForUser(any()) }
        coVerify(exactly = 0) { diskCache.read(any(), any(), any()) }
        coVerify(exactly = 0) { diskCache.replace(any(), any(), any()) }
    }

    @Test
    fun `disabling persists OFF advances RAM generation and clears every account`() = runTest {
        cache.setEnabled(true)
        val oldStamp = sessionCache.capture("alice")
        sessionCache.write(oldStamp, 42, 1, thread())

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

        assertTrue(failure is IllegalStateException)
        assertFalse(cache.isEnabled())
        cache.reconcilePendingPurge()
        coVerify(exactly = 2) { diskCache.clearAll() }
    }

    @Test
    fun `logout purge is skipped while OFF and scheduled globally after an enabled failure`() = runTest {
        cache.purgeForUser("alice")
        coVerify(exactly = 0) { diskCache.clearForUser(any()) }

        cache.setEnabled(true)
        coEvery { diskCache.clearForUser("alice") } throws IllegalStateException("unavailable")
        assertTrue(runCatching { cache.purgeForUser("alice") }.isFailure)

        cache.reconcilePendingPurge()
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

        val reconciliation = async { cache.reconcilePendingPurge() }
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

    private fun thread() = PrivateMessageThread(
        threadId = 42,
        subject = "subject",
        correspondent = "correspondent",
        messages = emptyList(),
        page = 1,
        totalPages = 1,
    )
}
