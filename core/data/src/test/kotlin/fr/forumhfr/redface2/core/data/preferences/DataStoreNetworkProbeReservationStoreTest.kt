package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreNetworkProbeReservationStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `reservation is atomic survives disk reopen and expires at one minute`() = runBlocking {
        val file = temporary.root.resolve("probe.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        var now = ProbeReservationTime(bootCount = 10, elapsedMillis = 100_000)
        val first = DataStoreNetworkProbeReservationStore(dataStore) { now }
        val second = DataStoreNetworkProbeReservationStore(dataStore) { now }
        try {
            val results = listOf(async { first.reserve() }, async { second.reserve() }).awaitAll()
            assertEquals(listOf(0L, 60L), results.sorted())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file })
        try {
            val afterProcessDeath = DataStoreNetworkProbeReservationStore(reopened) { now }
            assertEquals(60L, afterProcessDeath.remainingSeconds())
            now = now.copy(elapsedMillis = 159_999)
            assertEquals(1L, afterProcessDeath.reserve())
            now = now.copy(elapsedMillis = 160_000)
            assertEquals(0L, afterProcessDeath.reserve())
            assertEquals(60L, afterProcessDeath.remainingSeconds())
            // A reboot cannot shorten the minute, regardless of any wall-clock adjustment.
            now = ProbeReservationTime(bootCount = 11, elapsedMillis = 500)
            assertEquals(60L, afterProcessDeath.reserve())
            now = now.copy(elapsedMillis = 60_000)
            assertEquals(0L, afterProcessDeath.reserve())
        } finally {
            reopenedScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
