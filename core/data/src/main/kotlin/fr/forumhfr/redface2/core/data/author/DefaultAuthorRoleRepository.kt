package fr.forumhfr.redface2.core.data.author

import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.domain.author.AuthorRoleRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Rôle HFR des auteurs (#1112, #221 — PR A/socle) — implémentation **hybride** de
 * [AuthorRoleRepository], deux sources anonymes best-effort (cf. `docs/specs/protocol-hfr.md`).
 *
 * **[getStaff] (primaire)** — l'annuaire staff GLOBAL (`HfrClient.getStaffResponsables`, parsé par
 * [HfrParser.parseStaffList]) : un unique GET donne `pseudo canonicalisé -> rôle`.
 * - **cache UNIQUE** [cachedStaff] (pas de LRU : un seul objet) + TTL 24 h ([CachePolicy.staffDirectory]) ;
 * - **single-flight unique** [staffInFlight] : les appels concurrents partagent le même GET ;
 * - clés canonicalisées via [canonicalizePseudo] (la canonicalisation est faite ICI, pas au parser) ;
 * - **échec réseau** ([IOException]) → servir le **cache périmé s'il existe** (sans avancer son
 *   timestamp), sinon `emptyMap()` — **jamais** un fallback « N profils » ;
 * - **parse vide** → ne pas écraser un cache valide (retenter au prochain écran).
 *
 * **[getRole] (secondaire)** — la page profil d'UN auteur (`HfrClient.getProfile`, parsé par
 * [HfrParser.parseAuthorRole]) : réservée à une demande explicite mono-utilisateur.
 * - **cache LRU borné** [MAX_CACHE_ENTRIES] par `profileId` + TTL 24 h ([CachePolicy.authorRole]),
 *   incluant le cache négatif (`null`) ;
 * - **single-flight par `profileId`** ; anti-écrasement périmé ([writeRoleCacheIfNewer]).
 *
 * Les deux sources partagent la **borne de parallélisme GLOBALE** [fetchSemaphore] (4 GET concurrents
 * max, tout confondu) et le **scope propriétaire** [fetchScope] (les `Deferred` survivent à
 * l'annulation d'un appelant et servent les autres). `IOException` (dont hérite `HfrServerException`)
 * → best-effort ; `CancellationException` et erreurs inattendues **remontent** ; **pas de Room**.
 */
@Singleton
class DefaultAuthorRoleRepository @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthorRoleRepository {

    /** Borne GLOBALE de GET concurrents (profil + annuaire confondus) — un seul sémaphore. */
    private val fetchSemaphore = Semaphore(MAX_CONCURRENCY)

    /**
     * Scope propriétaire des fetches, distinct des appelants : un [Deferred] enregistré ici survit à
     * l'annulation de l'appelant qui l'a lancé et reste attendable par les appels concurrents
     * (single-flight). `SupervisorJob` : un fetch en échec ne détruit pas le scope. Jamais annulé.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // ─── Source primaire : annuaire staff global (cache unique) ────────────────
    // [staffLock] couvre À LA FOIS [cachedStaff] ET [staffInFlight] : la lecture du cache et le
    // choix/enregistrement du flight se font dans UNE seule section critique (anti-TOCTOU).
    private val staffLock = Any()
    private var cachedStaff: CachedStaff? = null
    private var staffInFlight: Deferred<Map<String, AuthorRole>>? = null

    // ─── Source secondaire : rôle par profileId (cache LRU) ────────────────────
    // [roleLock] couvre À LA FOIS [roleCache] (cache négatif inclus) ET [roleInFlight] : idem,
    // décision cache↔flight atomique pour ne pas relancer un 2e GET dans la fenêtre entre les deux.
    private val roleLock = Any()
    private val roleCache = object : LinkedHashMap<Int, CachedRole>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedRole>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val roleInFlight = HashMap<Int, Deferred<FetchResult>>()

    // ─── getStaff (primaire) ───────────────────────────────────────────────────

    /**
     * Décision **atomique** sous [staffLock] : (1) cache frais → le retourner ; (2) sinon flight en
     * vol → l'attendre ; (3) sinon créer + enregistrer le flight. L'await se fait **hors** verrou.
     * Écrire cache-check et flight-check dans la MÊME section critique ferme la fenêtre TOCTOU où un
     * appelant, ayant raté le cache, relancerait un 2e GET après qu'un autre a rempli le cache et
     * retiré son flight.
     */
    override suspend fun getStaff(): Map<String, AuthorRole> {
        val now = clock.instant()
        val deferred: Deferred<Map<String, AuthorRole>> = synchronized(staffLock) {
            val entry = cachedStaff
            if (entry != null && CachePolicy.isFresh(entry.fetchedAt, CachePolicy.staffDirectory, now)) {
                return entry.roles
            }
            staffInFlight?.takeIf { it.isActive }
                ?: fetchScope.async { doFetchStaff() }.also { started ->
                    staffInFlight = started
                    started.invokeOnCompletion { forgetStaffInFlight(started) }
                }
        }
        return deferred.await()
    }

    private fun forgetStaffInFlight(started: Deferred<Map<String, AuthorRole>>) {
        synchronized(staffLock) { if (staffInFlight === started) staffInFlight = null }
    }

    private suspend fun doFetchStaff(): Map<String, AuthorRole> {
        val stamp = clock.instant()
        val fetched: Map<String, AuthorRole>? = try {
            val html = fetchSemaphore.withPermit { client.getStaffResponsables() }
            // Choix documenté « dernière entrée gagne » : si deux pseudos bruts DISTINCTS
            // canonicalisent vers la même clé (collision improbable — HFR garantit l'unicité des
            // pseudos, et la canonicalisation ne fait que replier casse/espaces/format), `mapKeys`
            // conserve la dernière valeur de l'ordre d'itération. Acceptable ici : une telle
            // collision viserait le même individu, donc le même rôle. Couvert par un test.
            parser.parseStaffList(html).mapKeys { (pseudo, _) -> canonicalizePseudo(pseudo) }
        } catch (@Suppress("SwallowedException") networkFailure: IOException) {
            // Best-effort : échec réseau → servir le cache périmé (via commitStaff), jamais throw.
            null
        }
        return commitStaff(fetched, stamp)
    }

    /**
     * Fusionne le résultat dans le cache staff sous [staffLock] :
     * - échec réseau (`null`) **ou** parse vide → **ne pas écraser** un cache valide ; renvoyer le
     *   périmé s'il existe (timestamp inchangé), sinon `emptyMap()` ;
     * - annuaire non vide → stocker, sauf si un fetch plus récent a déjà atterri (anti-écrasement).
     */
    private fun commitStaff(fetched: Map<String, AuthorRole>?, stamp: Instant): Map<String, AuthorRole> =
        synchronized(staffLock) {
            val existing = cachedStaff
            when {
                fetched.isNullOrEmpty() -> existing?.roles ?: emptyMap()
                existing == null || !existing.fetchedAt.isAfter(stamp) -> {
                    cachedStaff = CachedStaff(fetched, stamp)
                    fetched
                }
                else -> existing.roles
            }
        }

    // ─── getRole (secondaire, par profileId) ───────────────────────────────────

    /**
     * Décision **atomique** sous [roleLock] (même invariant anti-TOCTOU que [getStaff]) : la lecture
     * du cache — **cache négatif inclus** (une entrée fraîche à rôle `null` est un hit, pas un miss) —
     * et le choix/enregistrement du flight se font dans la MÊME section critique. L'await est hors
     * verrou.
     */
    override suspend fun getRole(profileId: Int): AuthorRole? {
        val now = clock.instant()
        val deferred: Deferred<FetchResult> = synchronized(roleLock) {
            val entry = roleCache[profileId]
            if (entry != null && CachePolicy.isFresh(entry.fetchedAt, CachePolicy.authorRole, now)) {
                return entry.role
            }
            roleInFlight[profileId]?.takeIf { it.isActive }
                ?: fetchScope.async { doFetchRole(profileId) }.also { started ->
                    roleInFlight[profileId] = started
                    started.invokeOnCompletion { forgetRoleInFlight(profileId, started) }
                }
        }
        return when (val result = deferred.await()) {
            is FetchResult.Resolved -> result.role
            FetchResult.Failed -> null
        }
    }

    private fun forgetRoleInFlight(id: Int, started: Deferred<FetchResult>) {
        synchronized(roleLock) { if (roleInFlight[id] === started) roleInFlight.remove(id) }
    }

    private suspend fun doFetchRole(id: Int): FetchResult {
        val stamp = clock.instant()
        val result = try {
            val html = fetchSemaphore.withPermit { client.getProfile(id) }
            FetchResult.Resolved(parser.parseAuthorRole(html))
        } catch (@Suppress("SwallowedException") networkFailure: IOException) {
            // Best-effort : échec réseau/HTTP → null pour l'appel, jamais mis en cache.
            FetchResult.Failed
        }
        if (result is FetchResult.Resolved) {
            writeRoleCacheIfNewer(id, result.role, stamp)
        }
        return result
    }

    /** Écrit sous [roleLock] sans écraser une entrée dont le timestamp est plus récent que [stamp]. */
    private fun writeRoleCacheIfNewer(id: Int, role: AuthorRole?, stamp: Instant) {
        synchronized(roleLock) {
            val existing = roleCache[id]
            if (existing == null || !existing.fetchedAt.isAfter(stamp)) {
                roleCache[id] = CachedRole(role, stamp)
            }
        }
    }

    private data class CachedRole(val role: AuthorRole?, val fetchedAt: Instant)

    private data class CachedStaff(val roles: Map<String, AuthorRole>, val fetchedAt: Instant)

    /**
     * Issue d'une résolution profil. [Resolved] porte un résultat HTTP valide (rôle mappé ou `null`
     * pour un statut non reconnu) — mis en cache. [Failed] est un échec réseau transitoire — jamais
     * mis en cache, mappé `null` pour l'appel.
     */
    private sealed interface FetchResult {
        data class Resolved(val role: AuthorRole?) : FetchResult
        data object Failed : FetchResult
    }

    private companion object {
        /** Plafond LRU du cache de rôles par profil (~512 auteurs). */
        private const val MAX_CACHE_ENTRIES = 512

        /** Borne GLOBALE des GET concurrents (profil + annuaire) pour ne pas marteler HFR. */
        private const val MAX_CONCURRENCY = 4

        private const val LOAD_FACTOR = 0.75f

        /** Dimensionné pour contenir [MAX_CACHE_ENTRIES] sans rehash avant éviction. */
        private const val INITIAL_CAPACITY = MAX_CACHE_ENTRIES * 4 / 3 + 1
    }
}
