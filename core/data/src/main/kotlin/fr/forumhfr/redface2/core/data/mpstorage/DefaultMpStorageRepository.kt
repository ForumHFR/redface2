package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
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
import okhttp3.FormBody

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
    private val envelopeWriter: MpStorageEnvelopeWriter,
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
     * WRITE path (#6, ADR-014 §4) — GUARDED, NOT OBSERVED LIVE.
     *
     * Locates the storage document (same deterministic discovery as [fetchStorage] : cached location,
     * else inbox scan for the conversation whose subject is EXACTLY [MpStorageRepository.STORAGE_SUBJECT_HASH]),
     * reads its first-post edit form, runs the read-modify-write on the RAW JSON tree
     * ([MpStorageEnvelopeWriter] — third-party namespaces preserved), enforces the
     * [MpStorageRepository.MAX_CONTENT_FORM_BYTES] cap, and builds the `bdd.php cat=prive` POST body.
     *
     * GUARD (structural, Codex review) : the public [writeBackFlag] runs with [post] = `false` → the POST is
     * NEVER sent (body built & validated only). The live POST is reachable ONLY through the module-internal,
     * test-only [writeBackFlagLive] — it is NOT on the public interface, so app/prod code cannot flip it. The
     * `bdd.php cat=prive` write contract is unconfirmed (device down). A located-but-unreadable document or a
     * [MpStorageWriteResult.TargetNotFound] is surfaced, NEVER repaired / created (ADR-014 §3 : creating a
     * fresh document would fork the cross-userscript storage).
     */
    override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
        runWriteBack(entry, post = false)

    /**
     * Module-INTERNAL, TEST-ONLY live POST path — deliberately NOT on the [MpStorageRepository] interface
     * so app/prod code cannot reach it (Codex review : the guard is structural, not a default parameter).
     * The `bdd.php cat=prive` write contract is unconfirmed — NOT OBSERVED LIVE.
     */
    internal suspend fun writeBackFlagLive(entry: MpStorageFlagEntry): MpStorageWriteResult =
        runWriteBack(entry, post = true)

    @Suppress("ReturnCount") // Guard clauses (auth / not-found / unreadable / oversize) + the prepared return.
    private suspend fun runWriteBack(entry: MpStorageFlagEntry, post: Boolean): MpStorageWriteResult {
        return withContext(ioDispatcher) {
            val owner = activePseudo()
                ?: return@withContext MpStorageWriteResult.TargetNotFound

            val location = locateForWrite(owner)
                ?: return@withContext MpStorageWriteResult.TargetNotFound
            val read = readFormAndDocument(location)
                ?: return@withContext MpStorageWriteResult.TargetNotFound
            val document = read.document
                ?: return@withContext MpStorageWriteResult.TargetUnreadable

            when (val outcome = envelopeWriter.upsertFlag(document.rawEnvelope, entry)) {
                MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope ->
                    // Defensive : the parser already accepted it, so this should not happen — but if the
                    // raw text is somehow not a JSON object we surface it, never overwrite (ADR-014 §3).
                    MpStorageWriteResult.TargetUnreadable

                is MpStorageEnvelopeWriter.Outcome.TooLarge -> {
                    val cap = MpStorageRepository.MAX_CONTENT_FORM_BYTES
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG,
                        "writeBackFlag oversize: ${outcome.sizeBytes} bytes > $cap",
                    )
                    MpStorageWriteResult.TooLarge(outcome.sizeBytes)
                }

                is MpStorageEnvelopeWriter.Outcome.Mutated ->
                    prepareAndMaybePost(location, read.form, outcome.body, post)
            }
        }
    }

    /**
     * Builds the `bdd.php cat=prive` [FormBody] from the mutated [body] and the parsed [form], then —
     * ONLY when [post] is `true` (the internal test-only path) — POSTs it. From the public [writeBackFlag]
     * ([post] = `false`) NO request is sent : the body is built and validated, and
     * [MpStorageWriteResult.Prepared] carries `posted = false`. The live POST branch is NOT OBSERVED LIVE.
     */
    private suspend fun prepareAndMaybePost(
        location: MpStorageLocation,
        form: ReplyForm,
        body: String,
        post: Boolean,
    ): MpStorageWriteResult {
        val formBody = buildPrivateMessageEditBody(location, form, body)
        if (!post) {
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "writeBackFlag dry-run: body built (${body.length} chars), POST skipped — not observed live",
            )
            return MpStorageWriteResult.Prepared(body = body, posted = false)
        }
        // GUARDED branch — reached only via the internal writeBackFlagLive (tests). The bdd.php cat=prive
        // write contract is unconfirmed; the response is intentionally NOT parsed here (no live
        // success/error capture). Surfaced as Prepared(posted = true).
        hfrClient.submitPrivateMessageEdit(formBody)
        diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "writeBackFlag POSTED (live, unconfirmed contract)")
        return MpStorageWriteResult.Prepared(body = body, posted = true)
    }

    /**
     * The `bdd.php cat=prive` POST body. Mirrors [fr.forumhfr.redface2.core.data.write.DefaultEditPostRepository]'s
     * edit body, with TWO deliberate differences :
     *  - `cat` is the String `"prive"` (the storage post lives under the private-message category ;
     *    the public edit flow passes an Int) — this is the whole reason for the dedicated wire method ;
     *  - `content_form` is the mutated JSON document, not user BBCode.
     * `numreponse` targets the storage first post, `numrep` stays empty, `sujet` / `hash_check` /
     * `verifrequet` / the preserved hidden fields come from the parsed edit form. `password` and
     * `delete` are hard-denied (never resend the deletion checkbox).
     */
    private fun buildPrivateMessageEditBody(
        location: MpStorageLocation,
        form: ReplyForm,
        contentForm: String,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val emitted = mutableSetOf<String>()
        val overrides = buildMap {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            put("content_form", contentForm)
            put("numreponse", location.numreponse.toString())
            put("numrep", "")
            // The private-message discriminator — String, unlike the public edit flow's Int cat.
            put("cat", "prive")
            put("post", location.threadId.toString())
            put("page", "1")
            put("sujet", form.sujet)
            form.msgIcon?.let { put("MsgIcon", it) }
        }
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Hard deny: never resend `password` (defence-in-depth; the parser already filters it)
            // nor the `delete=1` checkbox the edit form ships (deletion is destructive, out of scope).
            if (key == "password" || key == "delete") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    /** Reading the storage edit form for the write path : the form + the parsed document (null = unreadable). */
    private data class WriteRead(val form: ReplyForm, val document: MpStorageDocument?)

    /**
     * GETs and parses the storage first-post edit form at [location] for the WRITE path, returning the
     * [ReplyForm] (for hash_check / sujet / hidden fields) plus the parsed [MpStorageDocument] (null when
     * the body is not a readable v0.1 envelope — surfaced as unreadable, never repaired). Returns `null`
     * when the edit form itself cannot be obtained (stale location). Throws [SessionExpiredException] on
     * an anonymous composer.
     */
    private suspend fun readFormAndDocument(location: MpStorageLocation): WriteRead? {
        val form = replyFormParser
            .parse(hfrClient.getPrivateMessageEditForm(location.threadId, location.numreponse))
            .getOrElse { error ->
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "write edit form parse FAILED: ${error::class.simpleName}",
                )
                return null
            }
        if (form.isAnonymous) {
            throw SessionExpiredException("MPStorage write edit form served anonymous composer")
        }
        val document = storageParser.parse(form.initialContent).getOrNull()
        return WriteRead(form = form, document = document)
    }

    /**
     * Resolves the storage location for the write path : the cached id if present, else a fresh inbox
     * scan (caching the discovery). Returns `null` when the account has no storage MP at all
     * (deterministic exact-hash miss → the caller maps it to [MpStorageWriteResult.TargetNotFound],
     * NEVER a creation — ADR-014 §3).
     */
    private suspend fun locateForWrite(owner: String): MpStorageLocation? {
        locationStore.read(owner)?.let { return it }
        return when (val discovery = discoverByInboxScan()) {
            is Discovery.Located -> discovery.location.also {
                locationStore.save(owner, it.threadId, it.numreponse)
            }
            // HitUnreadable = the conversation exists but its first post is unreachable. There is no
            // numreponse to address an edit, so the write has no valid target — treat as not-found
            // for the write path (still never creates a document).
            Discovery.HitUnreadable, Discovery.Absent -> null
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
            // Never let the scan bound SHRINK across pages: HFR's pager only shows a window around
            // the current page, so a later page can report a smaller `totalPages` than page 1 did.
            // Taking the running max keeps a deep storage MP reachable even if one page under-reports
            // the count (defence-in-depth alongside the cryptlink-aware pager parse, #6).
            totalPages = maxOf(totalPages, listPage.totalPages)
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
