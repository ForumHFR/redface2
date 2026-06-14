package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Default [MpStorageRepository] (#6, ADR-014) — read-only, zero writes.
 *
 * Discovery matches the de-facto userscript contract (`MPStorage.user.js` / DTCloud), NOT a
 * title search (HFR's subject index never returns the 32-hex hash, so the search-based discovery
 * of the original #406 implementation reported `NotFound` on every real account):
 *
 *  1. **Cached location** — if the storage MP was already located for this account
 *     ([MpStorageLocationStore]), go straight to the first post's edit form. This mirrors the
 *     userscript caching `mpId`/`mpRepId` after the first discovery instead of re-scanning.
 *  2. **Inbox scan** — otherwise walk the MP inbox (`forum1.php?cat=prive`, page by page up to
 *     [MAX_DISCOVERY_PAGES]) and match a conversation whose SUBJECT equals the fixed storage hash.
 *     The first post's `numreponse` (from the conversation page) completes the location, which is
 *     then cached. No match across the inbox → [MpStorageResult.NotFound] (the nominal case).
 *  3. **Read** — GET the edit form of that first post → `content_form` (raw JSON) → [MpStorageParser].
 *
 * An unreadable document is surfaced ([MpStorageResult.Unreadable]), NEVER repaired (ADR-014: the
 * original library's destructive reset-to-default trap). Diagnostics never log document content
 * (it aggregates private reading positions from every userscript) — only presence flags, sizes and
 * failure classes (#316 stance).
 */
@Singleton
// LongParameterList: one dep per pipeline stage (inbox/thread/edit parsers) + cache, auth, diagnostics, dispatcher.
@Suppress("LongParameterList")
class DefaultMpStorageRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val listParser: PrivateMessageListParser,
    private val threadParser: PrivateMessageThreadParser,
    private val replyFormParser: ReplyFormParser,
    private val storageParser: MpStorageParser,
    private val locationStore: MpStorageLocationStore,
    private val authRepository: AuthRepository,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MpStorageRepository {

    @Suppress("ReturnCount") // Guard clauses (not-authenticated / cache hit / no storage) + trailing read.
    override suspend fun fetchStorage(): MpStorageResult {
        return try {
            withContext(ioDispatcher) {
                val owner = activePseudo()
                if (owner == null) {
                    diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "fetchStorage: not authenticated")
                    return@withContext MpStorageResult.NotFound
                }

                // 1. Cached location → read directly (the userscript caches mpId after discovery).
                val cached = locationStore.read(owner)
                if (cached != null) {
                    val result = readFromLocation(cached)
                    if (result != null) return@withContext result
                    // The cached post no longer yields an edit form (conversation moved/removed) —
                    // drop the stale id and fall back to a fresh discovery.
                    diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "cached location stale → rediscover")
                    locationStore.clear(owner)
                }

                // 2. Discover by scanning the MP inbox for the fixed storage subject.
                when (val discovery = discoverByInboxScan()) {
                    Discovery.Absent -> {
                        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "discovery: no storage MP")
                        MpStorageResult.NotFound
                    }
                    Discovery.HitUnreadable -> {
                        // The subject matched but its first post is unreachable (empty thread / DOM
                        // drift) : the storage MP EXISTS, so surface Unreadable — never NotFound.
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "discovery: storage MP found but first post unreadable",
                        )
                        MpStorageResult.Unreadable
                    }
                    is Discovery.Located -> {
                        val location = discovery.location
                        locationStore.save(owner, location.threadId, location.numreponse)
                        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "discovery: storage MP found and cached")
                        // A located post that cannot be read back is surfaced as Unreadable.
                        readFromLocation(location) ?: MpStorageResult.Unreadable
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "fetchStorage SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "fetchStorage FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    /**
     * Reads the storage document at [location]. Returns the parsed outcome ([MpStorageResult.Found]
     * / [MpStorageResult.Unreadable]), or `null` when the edit form itself could not be obtained —
     * i.e. the location is STALE (the conversation moved or was removed). Throws
     * [SessionExpiredException] when the session evaporated mid-pipeline.
     */
    private suspend fun readFromLocation(location: MpStorageLocation): MpStorageResult? {
        val form = replyFormParser
            .parse(hfrClient.getPrivateMessageEditForm(location.threadId, location.numreponse))
            .getOrElse { error ->
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "edit form parse FAILED: ${error::class.simpleName}",
                )
                return null
            }
        if (form.isAnonymous) {
            // The session evaporated between discovery and the edit form GET.
            throw SessionExpiredException("MPStorage edit form served anonymous composer")
        }

        return storageParser.parse(form.initialContent).fold(
            onSuccess = { document ->
                diagnostics.record(
                    DiagnosticsLog.Level.INFO,
                    LOG_TAG,
                    "storage parsed: flags=${document.mpFlags.size} rawSize=${document.rawEnvelope.length}",
                )
                MpStorageResult.Found(document)
            },
            onFailure = { error ->
                // ADR-014 : an unreadable document is surfaced, NEVER repaired.
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "storage document unreadable: ${error::class.simpleName}",
                )
                MpStorageResult.Unreadable
            },
        )
    }

    /**
     * Walks the MP inbox page by page, matching a conversation whose SUBJECT is the fixed storage
     * hash (the userscript scans the inbox list client-side — there is no working server-side
     * subject search for the hash). Returns the located document position, or `null` when the
     * subject is absent from the whole inbox (bounded by [MAX_DISCOVERY_PAGES]).
     */
    private suspend fun discoverByInboxScan(): Discovery {
        var page = 1
        var totalPages = 1
        var outcome: Discovery? = null
        while (outcome == null && page <= totalPages && page <= MAX_DISCOVERY_PAGES) {
            val listPage = listParser.parseList(hfrClient.getPrivateMessageListPage(page))
            totalPages = listPage.totalPages
            val hit = listPage.items.firstOrNull { it.subject == MpStorageRepository.STORAGE_SUBJECT_HASH }
            if (hit != null) {
                // The subject is matched : the scan ends here whether or not the first post is
                // readable (a located-but-unreadable conversation must not trigger a full sweep).
                val numreponse = firstPostNumreponse(hit.threadId)
                outcome = numreponse
                    ?.let { Discovery.Located(MpStorageLocation(threadId = hit.threadId, numreponse = it)) }
                    ?: Discovery.HitUnreadable
            }
            page++
        }

        if (outcome == null && totalPages > MAX_DISCOVERY_PAGES) {
            // No silent cap: an inbox deeper than the scan bound is reported, not hidden.
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "discovery stopped at page cap $MAX_DISCOVERY_PAGES of $totalPages",
            )
        }
        return outcome ?: Discovery.Absent
    }

    /** Outcome of the inbox scan: subject absent, found-but-unreadable, or fully located. */
    private sealed interface Discovery {
        data object Absent : Discovery
        data object HitUnreadable : Discovery
        data class Located(val location: MpStorageLocation) : Discovery
    }

    private suspend fun firstPostNumreponse(threadId: Int): Int? =
        threadParser
            .parse(hfrClient.getPrivateMessageThreadPage(threadId, page = 1))
            .messages
            .firstOrNull()
            ?.numreponse

    private suspend fun activePseudo(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo

    private companion object {
        private const val LOG_TAG = "MpStorageRepository"

        /**
         * Inbox pages the discovery scan walks before giving up. The storage subject is a fixed hash
         * that is never bumped, so an old storage MP can sit deep in the inbox; 50 pages (~1500
         * conversations at HFR's page size) covers any realistic account while bounding a pathological
         * scan. Reached only once per account (the location is then cached).
         */
        private const val MAX_DISCOVERY_PAGES = 50
    }
}
