package fr.forumhfr.redface2.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.matches
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local super-favorite store (#603). Reuses the shared `@UserPreferencesDataStore` Preferences store
 * under a dedicated per-account key (no second DataStore file). Entries are persisted as a
 * `Set<String>` (the only set type DataStore Preferences offers), with a versioned
 * `(cat, topicId, subcat, title)` encoding. The former topic-id-only values are still decoded as
 * orphan snapshots so no user pin is lost.
 *
 * The pre-#1270 global key is never claimed by the anonymous pseudo-account (#1319): it waits for
 * the first *authenticated* account, while logged-out observers only read it as a fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DataStoreSuperFavoriteRepository @Inject constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    private val authRepository: AuthRepository,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : SuperFavoriteRepository {

    private val writeMutex = Mutex()

    override fun observeSuperFavoriteTopics(): Flow<Set<SuperFavoriteTopic>> =
        authRepository.observeAuthState()
            .map(::accountId)
            .distinctUntilChanged()
            .flatMapLatest { accountId ->
                flow {
                    adoptPendingEntriesIfNeeded(accountId)
                    emitAll(
                        dataStore.data.map { prefs ->
                            prefs.superFavoriteEntries(accountId).mapNotNull(::decodeEntry).toSet()
                        },
                    )
                }
            }
            .distinctUntilChanged()
            // Audit #1 — rethrow CancellationException before degrading: a bare `catch { emit(...) }`
            // swallows ALL throwables, including the cooperative cancellation that structured
            // concurrency relies on (mirrors DefaultFlagRepository.fetchStickyFlagSupplement). Only a
            // genuine DataStore read error degrades to an empty set (e.g. corrupt prefs file).
            .catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not read super-favorite topics; degrading to empty set", e)
                emit(emptySet())
            }

    override suspend fun setSuperFavorite(flag: Flag, enabled: Boolean) {
        updateSuperFavorites { current ->
            val withoutFlag = current.filterNot { it.matches(flag) }.toSet()
            if (enabled) withoutFlag + flag.toSuperFavoriteTopic() else withoutFlag
        }
    }

    override suspend fun toggleSuperFavorite(flag: Flag) {
        updateSuperFavorites { current ->
            val withoutFlag = current.filterNot { it.matches(flag) }.toSet()
            if (withoutFlag.size == current.size) {
                withoutFlag + flag.toSuperFavoriteTopic()
            } else {
                withoutFlag
            }
        }
    }

    private suspend fun updateSuperFavorites(
        transform: (Set<SuperFavoriteTopic>) -> Set<SuperFavoriteTopic>,
    ) {
        val accountId = accountId(authRepository.observeAuthState().first())
        // Parented to the process-lifetime scope (cf. DataStoreUserPreferencesRepository.persist) so a
        // long-press sheet dismissed mid-write still commits the toggle. The mutex makes toggle a
        // read-current-then-write operation even when two callers hit DataStore before its flow emits.
        externalScope.async {
            writeMutex.withLock {
                dataStore.edit { prefs ->
                    prefs.adoptPendingEntriesIfNeeded(accountId)
                    val current = prefs.superFavoriteEntries(accountId)
                        .mapNotNull(::decodeEntry)
                        .toSet()
                    prefs[superFavoriteKey(accountId)] =
                        transform(current).mapTo(mutableSetOf(), ::encodeEntry)
                }
            }
        }.await()
    }

    /** No-op for the anonymous account, so a logged-out read never opens a write transaction. */
    private suspend fun adoptPendingEntriesIfNeeded(accountId: String) {
        if (accountId == ANONYMOUS_ACCOUNT_ID) return
        writeMutex.withLock {
            dataStore.edit { prefs -> prefs.adoptPendingEntriesIfNeeded(accountId) }
        }
    }
}

private const val TAG = "SuperFavoriteRepo"
private const val VERSION = "v1"
private const val FIELD_SEPARATOR = "|"
private const val NULL_SUBCAT = "_"
private const val ANONYMOUS_ACCOUNT_ID = "anonymous"
private const val SUPER_FAVORITE_KEY_PREFIX = "super_favorite_topic_ids_"
private val LEGACY_SUPER_FAVORITE_KEY = stringSetPreferencesKey("super_favorite_topic_ids")
private val ANONYMOUS_SUPER_FAVORITE_KEY =
    stringSetPreferencesKey("$SUPER_FAVORITE_KEY_PREFIX$ANONYMOUS_ACCOUNT_ID")
private val TITLE_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val TITLE_DECODER: Base64.Decoder = Base64.getUrlDecoder()

private fun accountId(state: AuthState): String =
    (state as? AuthState.Authenticated)?.pseudo?.lowercase() ?: ANONYMOUS_ACCOUNT_ID

private fun superFavoriteKey(accountId: String): Preferences.Key<Set<String>> =
    stringSetPreferencesKey("$SUPER_FAVORITE_KEY_PREFIX$accountId")

/**
 * Entries an account observes: its own key, or — for the anonymous pseudo-account only — the
 * pre-#1270 global key as a **read-only** fallback (#1319), so a user opening the app logged out
 * after the upgrade still sees the pins that are waiting for their real account.
 */
private fun Preferences.superFavoriteEntries(accountId: String): Set<String> {
    val own = this[superFavoriteKey(accountId)]
    val legacyFallback = if (accountId == ANONYMOUS_ACCOUNT_ID) {
        this[LEGACY_SUPER_FAVORITE_KEY]
    } else {
        null
    }
    return own ?: legacyFallback.orEmpty()
}

/**
 * Moves the entries a fresh authenticated account should own into its key. The anonymous
 * pseudo-account is deliberately excluded: before #1319 it could claim (and thus hide) the legacy
 * global key of a user whose HFR session had simply expired.
 */
private fun MutablePreferences.adoptPendingEntriesIfNeeded(accountId: String) {
    if (accountId == ANONYMOUS_ACCOUNT_ID || this[superFavoriteKey(accountId)] != null) return
    val sourceKey = pendingSuperFavoriteSourceKey()
    if (sourceKey != null) {
        this[superFavoriteKey(accountId)] = this[sourceKey].orEmpty()
        remove(sourceKey)
    }
}

private fun Preferences.pendingSuperFavoriteSourceKey(): Preferences.Key<Set<String>>? = when {
    this[LEGACY_SUPER_FAVORITE_KEY] != null -> LEGACY_SUPER_FAVORITE_KEY
    canRescueAnonymousEntries() -> ANONYMOUS_SUPER_FAVORITE_KEY
    else -> null
}

/**
 * One-shot rescue for the installs already hit by #1319 on the dev channel: there, opening the app
 * logged out moved the legacy global key to `..._anonymous` and deleted it, so the account key of
 * the pins' real owner stays empty forever. Adopting the anonymous set is only safe while no other
 * account has claimed a set yet — otherwise a second account signing in would steal pins that the
 * first one legitimately made while logged out.
 */
private fun Preferences.canRescueAnonymousEntries(): Boolean =
    !this[ANONYMOUS_SUPER_FAVORITE_KEY].isNullOrEmpty() && !hasAccountScopedEntries()

private fun Preferences.hasAccountScopedEntries(): Boolean =
    asMap().keys.any { key ->
        key.name.startsWith(SUPER_FAVORITE_KEY_PREFIX) && key.name != ANONYMOUS_SUPER_FAVORITE_KEY.name
    }

private fun Flag.toSuperFavoriteTopic(): SuperFavoriteTopic = SuperFavoriteTopic(
    cat = cat,
    topicId = topicId,
    title = title,
    subcat = subcat,
)

private fun encodeEntry(topic: SuperFavoriteTopic): String {
    val cat = topic.cat
    if (cat == null) return topic.topicId.toString()
    val subcat = topic.subcat?.toString() ?: NULL_SUBCAT
    val encodedTitle = TITLE_ENCODER.encodeToString(
        topic.title.orEmpty().toByteArray(Charsets.UTF_8),
    )
    return listOf(
        VERSION,
        cat.toString(),
        topic.topicId.toString(),
        subcat,
        encodedTitle,
    ).joinToString(FIELD_SEPARATOR)
}

private fun decodeEntry(raw: String): SuperFavoriteTopic? =
    decodeLegacyEntry(raw) ?: decodeVersionedEntry(raw)

private fun decodeLegacyEntry(raw: String): SuperFavoriteTopic? =
    raw.toIntOrNull()?.let { legacyId ->
        SuperFavoriteTopic(cat = null, topicId = legacyId, title = null, subcat = null)
    }

private fun decodeVersionedEntry(raw: String): SuperFavoriteTopic? {
    val parts = raw.split(FIELD_SEPARATOR, limit = 5)
    val hasExpectedShape = parts.size == 5 && parts[0] == VERSION
    val cat = parts.getOrNull(1)?.takeIf { hasExpectedShape }?.toIntOrNull()
    val topicId = parts.getOrNull(2)?.takeIf { hasExpectedShape }?.toIntOrNull()
    return if (cat != null && topicId != null) {
        SuperFavoriteTopic(
            cat = cat,
            topicId = topicId,
            title = parts[4].decodeTitle(),
            subcat = parts[3].decodeSubcat(),
        )
    } else {
        null
    }
}

private fun String.decodeSubcat(): Int? =
    takeUnless { it == NULL_SUBCAT }?.toIntOrNull()

private fun String.decodeTitle(): String? =
    runCatching {
        String(TITLE_DECODER.decode(this), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
