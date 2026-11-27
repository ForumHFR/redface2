package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.network.HfrRestCircuitBreaker
import fr.forumhfr.redface2.core.network.HfrRestContext
import fr.forumhfr.redface2.core.network.HfrRestCooldown
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreHfrRestCooldownStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `deadline level and authentication context persist together and can be cleared`() = runBlocking {
        val file = temporary.root.resolve("user.preferences_pb")
        val saved = HfrRestCooldown(Instant.parse("2026-11-27T12:20:00Z"), level = 1, HfrRestContext.AUTHENTICATED)

        val writing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreHfrRestCooldownStore(PreferenceDataStoreFactory.create(scope = writing) { file })
            assertNull(store.read())
            store.write(saved)
        } finally {
            writing.coroutineContext[Job]?.cancelAndJoin()
        }

        val reopened = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreHfrRestCooldownStore(PreferenceDataStoreFactory.create(scope = reopened) { file })
            assertEquals(saved, store.read())
            store.write(null)
            assertNull(store.read())
        } finally {
            reopened.coroutineContext[Job]?.cancelAndJoin()
        }
    }
    @Test
    fun `a corrupt preferences file leaves the REST breaker available and logs once`() = runBlocking {
        val file = temporary.root.resolve("corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x80.toByte()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreHfrRestCooldownStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            val log = DiagnosticsLog()
            val breaker = HfrRestCircuitBreaker(store, Clock.systemUTC(), log)
            repeat(2) {
                breaker.checkProbeAllowed()
                assertNull(breaker.cooldownDeadline())
            }
            assertEquals(1, log.entries.value.count { it.message.contains("CorruptionException") })
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

}
