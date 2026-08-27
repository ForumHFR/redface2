package fr.forumhfr.redface2.core.data.author

import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rôle HFR (#1112, #221 — PR A) — tests unitaires de [DefaultAuthorRoleRepository] hybride.
 *
 * Couvre `getStaff` (annuaire primaire : 1 GET, canonicalisation, TTL, single-flight,
 * IOException→périmé/vide, pas d'écrasement par un parse vide) et `getRole` (profil secondaire :
 * cache LRU/TTL/négatif, best-effort IOException vs erreur inattendue, concurrence borne globale +
 * single-flight), plus le partage de la borne de parallélisme entre les deux sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthorRoleRepositoryTest {

    private val client = mockk<HfrClient>()
    private val parser = mockk<HfrParser>()
    private val clock = MutableClock(Instant.parse("2026-08-27T10:00:00Z"))

    // Instrumentation des tests de concurrence (client gaté sur un unique gate de libération).
    private val liveGets = AtomicInteger(0)
    private val maxLiveGets = AtomicInteger(0)
    private val getsPerKey = ConcurrentHashMap<Int, Int>()
    private val releaseGate = CompletableDeferred<Unit>()

    private fun repository(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) =
        DefaultAuthorRoleRepository(
            client = client,
            parser = parser,
            clock = clock,
            ioDispatcher = dispatcher,
        )

    /** Compte les GET (par clé + concurrence max) puis suspend jusqu'à [releaseGate]. */
    private suspend fun enterGate(key: Int) {
        getsPerKey.merge(key, 1, Int::plus)
        val current = liveGets.incrementAndGet()
        maxLiveGets.getAndUpdate { max(it, current) }
        releaseGate.await()
        liveGets.decrementAndGet()
    }

    /** Stub gaté de `getProfile` (clé = profileId) ET `getStaffResponsables` (clé = [STAFF_KEY]). */
    private fun installGatedClient() {
        coEvery { client.getProfile(any()) } coAnswers {
            val id = firstArg<Int>()
            enterGate(id)
            "<html>$id</html>"
        }
        coEvery { client.getStaffResponsables() } coAnswers {
            enterGate(STAFF_KEY)
            STAFF_HTML
        }
        every { parser.parseAuthorRole(any()) } returns AuthorRole.MODERATOR
        every { parser.parseStaffList(any()) } returns mapOf("x" to AuthorRole.MODERATOR)
    }

    // ─── getStaff (annuaire primaire) ──────────────────────────────────────────

    @Test
    fun `getStaff returns the canonicalized directory from a single GET`() = runTest {
        val html = "<html>staff</html>"
        coEvery { client.getStaffResponsables() } returns html
        every { parser.parseStaffList(html) } returns mapOf(
            "Ernestor" to AuthorRole.MODERATOR,
            "La Monne" to AuthorRole.ADMIN,
            "antp" to AuthorRole.ADMIN,
        )

        val staff = repository().getStaff()

        // Clés canonicalisées (collapse espaces + lowercase), rôles préservés.
        assertEquals(
            mapOf(
                "ernestor" to AuthorRole.MODERATOR,
                "la monne" to AuthorRole.ADMIN,
                "antp" to AuthorRole.ADMIN,
            ),
            staff,
        )
        coVerify(exactly = 1) { client.getStaffResponsables() }
    }

    @Test
    fun `getStaff serves a fresh cache hit without a second GET`() = runTest {
        val html = "<html>staff</html>"
        coEvery { client.getStaffResponsables() } returns html
        every { parser.parseStaffList(html) } returns mapOf("Ernestor" to AuthorRole.MODERATOR)
        val repo = repository()

        repo.getStaff()
        clock.advance(Duration.ofHours(1)) // toujours dans le TTL de 24h
        repo.getStaff()

        coVerify(exactly = 1) { client.getStaffResponsables() }
    }

    @Test
    fun `getStaff refetches after the 24h TTL expires`() = runTest {
        val html = "<html>staff</html>"
        coEvery { client.getStaffResponsables() } returns html
        every { parser.parseStaffList(html) } returns mapOf("Ernestor" to AuthorRole.MODERATOR)
        val repo = repository()

        repo.getStaff()
        clock.advance(Duration.ofHours(24).plusSeconds(1))
        repo.getStaff()

        coVerify(exactly = 2) { client.getStaffResponsables() }
    }

    @Test
    fun `getStaff returns an empty map on network failure with no cache`() = runTest {
        coEvery { client.getStaffResponsables() } throws IOException("network down")

        val staff = repository().getStaff()

        assertEquals(emptyMap<String, AuthorRole>(), staff)
    }

    @Test
    fun `getStaff serves the stale cache on network failure and retries next time`() = runTest {
        val html = "<html>staff</html>"
        var call = 0
        coEvery { client.getStaffResponsables() } coAnswers {
            call += 1
            if (call == 2) throw IOException("down") else html
        }
        every { parser.parseStaffList(html) } returns mapOf("Ernestor" to AuthorRole.MODERATOR)
        val repo = repository()

        repo.getStaff() // GET #1 → cache
        clock.advance(Duration.ofHours(24).plusSeconds(1)) // périmé
        val onFailure = repo.getStaff() // GET #2 → IOException → sert le périmé, timestamp inchangé
        val afterRetry = repo.getStaff() // toujours périmé → GET #3 → recharge

        assertEquals(mapOf("ernestor" to AuthorRole.MODERATOR), onFailure) // périmé servi
        assertEquals(mapOf("ernestor" to AuthorRole.MODERATOR), afterRetry)
        coVerify(exactly = 3) { client.getStaffResponsables() } // l'échec n'a PAS avancé le timestamp
    }

    @Test
    fun `getStaff does not overwrite a valid cache with an empty parse`() = runTest {
        var call = 0
        coEvery { client.getStaffResponsables() } coAnswers { if (++call == 1) "h1" else "h2" }
        every { parser.parseStaffList("h1") } returns mapOf("Ernestor" to AuthorRole.MODERATOR)
        every { parser.parseStaffList("h2") } returns emptyMap()
        val repo = repository()

        repo.getStaff() // GET #1 → {ernestor}
        clock.advance(Duration.ofHours(24).plusSeconds(1)) // périmé
        val afterEmpty = repo.getStaff() // GET #2 → parse VIDE → garde le cache valide

        assertEquals(mapOf("ernestor" to AuthorRole.MODERATOR), afterEmpty)
        coVerify(exactly = 2) { client.getStaffResponsables() }
    }

    @Test
    fun `getStaff coalesces concurrent calls into a single GET`() = runTest {
        installGatedClient()
        val repo = repository(StandardTestDispatcher(testScheduler))

        launch { repo.getStaff() }
        launch { repo.getStaff() }
        advanceUntilIdle() // un leader au gate, un suiveur sur le même Deferred

        assertEquals("un seul GET annuaire malgré 2 appels concurrents", 1, getsPerKey[STAFF_KEY])

        releaseGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `getStaff re-throws CancellationException`() = runTest {
        coEvery { client.getStaffResponsables() } throws CancellationException("cooperative cancel")

        var caught: Throwable? = null
        try {
            repository().getStaff()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            caught = t
        }

        assertTrue("CancellationException doit remonter, obtenu $caught", caught is CancellationException)
    }

    @Test
    fun `getStaff lets an unexpected error propagate instead of returning empty`() = runTest {
        // Un bug (non-IOException) NE doit PAS être avalé en emptyMap best-effort : il remonte.
        coEvery { client.getStaffResponsables() } throws IllegalStateException("bug")

        val error = runCatching { repository().getStaff() }.exceptionOrNull()

        assertTrue("une erreur inattendue doit remonter, obtenu $error", error is IllegalStateException)
    }

    @Test
    fun `getStaff resolves a canonicalization collision as last-entry-wins`() = runTest {
        val html = "<html>staff</html>"
        coEvery { client.getStaffResponsables() } returns html
        // « Foo » et « foo » (espace final) canonicalisent tous deux vers "foo" : dernière gagne.
        every { parser.parseStaffList(html) } returns linkedMapOf(
            "Foo" to AuthorRole.MODERATOR,
            "foo " to AuthorRole.ADMIN,
        )

        val staff = repository().getStaff()

        assertEquals(mapOf("foo" to AuthorRole.ADMIN), staff)
    }

    // ─── getRole (profil secondaire) ───────────────────────────────────────────

    @Test
    fun `getRole caches a fresh result without a second GET`() = runTest {
        val html = "<html>modo</html>"
        coEvery { client.getProfile(MODO_ID) } returns html
        every { parser.parseAuthorRole(html) } returns AuthorRole.MODERATOR
        val repo = repository()

        val first = repo.getRole(MODO_ID)
        clock.advance(Duration.ofHours(1))
        val second = repo.getRole(MODO_ID)

        assertEquals(AuthorRole.MODERATOR, first)
        assertEquals(AuthorRole.MODERATOR, second)
        coVerify(exactly = 1) { client.getProfile(MODO_ID) }
    }

    @Test
    fun `getRole refetches after the 24h TTL expires`() = runTest {
        val html = "<html>modo</html>"
        coEvery { client.getProfile(MODO_ID) } returns html
        every { parser.parseAuthorRole(html) } returns AuthorRole.MODERATOR
        val repo = repository()

        repo.getRole(MODO_ID)
        clock.advance(Duration.ofHours(24).plusSeconds(1))
        repo.getRole(MODO_ID)

        coVerify(exactly = 2) { client.getProfile(MODO_ID) }
    }

    @Test
    fun `getRole caches an unknown status negatively`() = runTest {
        val html = "<html>inconnu</html>"
        coEvery { client.getProfile(MEMBER_ID) } returns html
        every { parser.parseAuthorRole(html) } returns null
        val repo = repository()

        val first = repo.getRole(MEMBER_ID)
        val second = repo.getRole(MEMBER_ID)

        assertNull(first)
        assertNull(second)
        coVerify(exactly = 1) { client.getProfile(MEMBER_ID) } // le null est caché négativement
    }

    @Test
    fun `getRole maps a network failure to null best-effort without caching it`() = runTest {
        coEvery { client.getProfile(MEMBER_ID) } throws IOException("network down")
        val repo = repository()

        val first = repo.getRole(MEMBER_ID)
        val second = repo.getRole(MEMBER_ID)

        assertNull("un échec réseau mappe à null", first)
        assertNull(second)
        coVerify(exactly = 2) { client.getProfile(MEMBER_ID) } // échec non caché → refetch
    }

    @Test
    fun `getRole re-throws CancellationException`() = runTest {
        coEvery { client.getProfile(MODO_ID) } throws CancellationException("cooperative cancel")

        var caught: Throwable? = null
        try {
            repository().getRole(MODO_ID)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            caught = t
        }

        assertTrue("CancellationException doit remonter, obtenu $caught", caught is CancellationException)
    }

    @Test
    fun `getRole lets an unexpected error propagate instead of mapping it to null`() = runTest {
        coEvery { client.getProfile(MODO_ID) } throws IllegalStateException("bug")

        val error = runCatching { repository().getRole(MODO_ID) }.exceptionOrNull()

        assertTrue("une erreur inattendue doit remonter, obtenu $error", error is IllegalStateException)
    }

    @Test
    fun `getRole coalesces concurrent calls for the same id into a single GET`() = runTest {
        installGatedClient()
        val repo = repository(StandardTestDispatcher(testScheduler))

        val results = mutableListOf<AuthorRole?>()
        launch { results += repo.getRole(SHARED_ID) }
        launch { results += repo.getRole(SHARED_ID) }
        advanceUntilIdle() // un leader au gate, un suiveur sur le même Deferred

        assertEquals("un seul GET pour l'id partagé", 1, getsPerKey[SHARED_ID])

        releaseGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, results.size)
        assertTrue(results.all { it == AuthorRole.MODERATOR })
    }

    // ─── Borne de parallélisme GLOBALE (partagée getRole + getStaff) ───────────

    @Test
    fun `concurrent GETs are capped at 4 globally across getRole and getStaff`() = runTest {
        installGatedClient()
        val repo = repository(StandardTestDispatcher(testScheduler))

        // 4 getRole (ids distincts) + 1 getStaff = 5 fetches ; le sémaphore PARTAGÉ borne à 4 en vol.
        (1..4).forEach { id -> launch { repo.getRole(id) } }
        launch { repo.getStaff() }
        advanceUntilIdle()

        assertEquals("borne globale de 4 GET concurrents, getRole+getStaff confondus", 4, maxLiveGets.get())

        releaseGate.complete(Unit)
        advanceUntilIdle()
    }

    // ─── Fenêtre TOCTOU cache↔flight sous VRAIE contention multi-thread ─────────
    // Le scheduler mono-thread (Standard/Unconfined) ne peut PAS entrelacer les deux ex-sections
    // critiques ; ces tests s'exécutent sur un vrai pool de threads avec un CyclicBarrier qui aligne
    // N appelants, pour exercer la course réelle. Avec la décision atomique (cache-check + flight
    // sous UN verrou, l'écriture cache précédant le retrait du flight), un appelant voit TOUJOURS soit
    // le cache frais soit le flight actif → EXACTEMENT 1 GET. Sans le fix (deux sections séparées), un
    // appelant pouvait rater le cache puis ne plus voir le flight et relancer un GET.

    @Test
    fun `concurrent same-id getRole never double-fetches under real thread contention`() {
        val pool = Executors.newFixedThreadPool(CONTENTION_THREADS + 1)
        val dispatcher = pool.asCoroutineDispatcher()
        val gets = AtomicInteger(0)
        val realClient = mockk<HfrClient>()
        val realParser = mockk<HfrParser>()
        coEvery { realClient.getProfile(any()) } coAnswers { gets.incrementAndGet(); "<html>x</html>" }
        every { realParser.parseAuthorRole(any()) } returns AuthorRole.MODERATOR
        val repo = DefaultAuthorRoleRepository(realClient, realParser, clock, dispatcher)
        try {
            repeat(CONTENTION_ITERATIONS) { id ->
                val barrier = CyclicBarrier(CONTENTION_THREADS)
                runBlocking(dispatcher) {
                    (1..CONTENTION_THREADS).map {
                        async { barrier.await(); repo.getRole(id) }
                    }.awaitAll()
                }
            }
            assertEquals("1 seul GET par id malgré la contention réelle", CONTENTION_ITERATIONS, gets.get())
        } finally {
            dispatcher.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent getStaff never double-fetches under real thread contention`() {
        val pool = Executors.newFixedThreadPool(CONTENTION_THREADS + 1)
        val dispatcher = pool.asCoroutineDispatcher()
        try {
            repeat(CONTENTION_ITERATIONS) {
                val gets = AtomicInteger(0)
                val realClient = mockk<HfrClient>()
                val realParser = mockk<HfrParser>()
                coEvery { realClient.getStaffResponsables() } coAnswers { gets.incrementAndGet(); "<html>staff</html>" }
                every { realParser.parseStaffList(any()) } returns mapOf("Ernestor" to AuthorRole.MODERATOR)
                // Cache staff UNIQUE : un repo neuf par itération pour re-provoquer la course à froid.
                val repo = DefaultAuthorRoleRepository(realClient, realParser, clock, dispatcher)
                val barrier = CyclicBarrier(CONTENTION_THREADS)
                runBlocking(dispatcher) {
                    (1..CONTENTION_THREADS).map {
                        async { barrier.await(); repo.getStaff() }
                    }.awaitAll()
                }
                assertEquals("1 seul GET annuaire malgré la contention réelle", 1, gets.get())
            }
        } finally {
            dispatcher.close()
            pool.shutdownNow()
        }
    }

    /**
     * [Clock] mutable minimal (même pattern que DefaultForumRepositoryTest) pour piloter le TTL sans
     * dépendre du chaînage déprécié `Clock.offset`.
     */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        private const val MODO_ID = 15461
        private const val MEMBER_ID = 15867
        private const val SHARED_ID = 42
        private const val STAFF_KEY = -1
        private const val STAFF_HTML = "<html>staff</html>"

        // Contention réelle : N threads alignés par un CyclicBarrier, sur plusieurs itérations.
        private const val CONTENTION_THREADS = 8
        private const val CONTENTION_ITERATIONS = 50
    }
}
