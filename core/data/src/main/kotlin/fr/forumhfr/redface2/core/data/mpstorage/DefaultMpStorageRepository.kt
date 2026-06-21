package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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
 * Default [MpStorageRepository] (#6, ADR-014) — read pipeline + the opt-in verify-after-write path.
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
 *
 * The WRITE path ([writeBackFlag]) is the REAL, opt-in path : gated by
 * [UserPreferencesRepository.observeSyncPrivateMessagesWriteEnabled] (default OFF), it locates the
 * document, backs up its verbatim `content_form`, runs the pure read-modify-write, POSTs (with the
 * active account's pseudo and the CONSTANT subject hash), then VERIFIES by re-reading — restoring the
 * backup on a mismatch. NOT OBSERVED LIVE : the `bdd.php cat=prive` write contract is unconfirmed.
 */
@Singleton
// LongParameterList: one dep per pipeline stage (inbox/thread/edit parsers) + cache, auth, prefs, diagnostics, disp.
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
    private val userPreferencesRepository: UserPreferencesRepository,
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
     * WRITE path (#6, ADR-014 §4) — REAL, opt-in (OFF by default), verify-after-write.
     *
     * The 15-step flow (cf. the spec) : read the pref → if OFF return [MpStorageWriteResult.DisabledByPreference]
     * (NO request) → resolve the active pseudo → GET the edit form + raw `content_form` → keep a VERBATIM backup
     * → refuse an unreadable JSON ([MpStorageWriteResult.Unreadable]) → run the pure writer → if no-op return
     * [MpStorageWriteResult.Success] (verified, no POST) → enforce the [MpStorageRepository.MAX_CONTENT_FORM_BYTES]
     * cap ([MpStorageWriteResult.TooLarge]) → POST the mutated body → RE-GET → if it equals the mutated body
     * [MpStorageWriteResult.Success] → otherwise re-POST the backup (restore) + re-GET, surfaced as
     * [MpStorageWriteResult.VerificationFailedRestored] / [MpStorageWriteResult.VerificationFailedRestoreFailed].
     *
     * Both the POST and the restore POST go through the SAME guarded builder ([buildPrivateMessageEditBody]) :
     * active-account pseudo + the CONSTANT subject hash, refusing to POST when the parsed form's subject is wrong.
     * The discovery is the same deterministic exact-hash match as [fetchStorage] ; a miss is
     * [MpStorageWriteResult.TargetNotFound], NEVER a creation (ADR-014 §3).
     */
    override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
        writeBack(entry, updateOnly = false, expectedPseudo = null)

    /**
     * UPDATE-ONLY write path (#597) — the AUTO reading-position trigger. Same pipeline / gates as
     * [writeBackFlag] but constrains the upsert to entries already present : an absent `threadId` is
     * declined with [MpStorageWriteResult.SkippedNotPresent] (no POST), so a passive page land never
     * pollutes the shared cross-userscript document with a Redface-2-invented entry (ADR-014 anti-doublon).
     *
     * IDENTITY GUARD (C2) — [expectedPseudo] is the account the caller decided to write under. After
     * re-resolving the active pseudo here, the write is DECLINED ([MpStorageWriteResult.SessionChanged],
     * no POST) when it no longer matches : the active account switched between the caller's check and
     * this call.
     */
    override suspend fun writeBackFlagIfPresent(
        entry: MpStorageFlagEntry,
        expectedPseudo: String,
    ): MpStorageWriteResult = writeBack(entry, updateOnly = true, expectedPseudo = expectedPseudo)

    // ReturnCount: guard clauses (pref / auth / identity / not-found / unreadable / skip / no-op / oversize / unsafe).
    @Suppress("ReturnCount")
    private suspend fun writeBack(
        entry: MpStorageFlagEntry,
        updateOnly: Boolean,
        expectedPseudo: String?,
    ): MpStorageWriteResult {
        return withContext(ioDispatcher) {
            // 1-2. Opt-in gate FIRST — no network when OFF (the default), so a missing trigger is harmless.
            if (!syncWriteEnabled()) {
                diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "writeBackFlag: opt-in OFF → no write")
                return@withContext MpStorageWriteResult.DisabledByPreference
            }

            // 3. IDENTITY GUARD (C2) FIRST — resolve the active account, then refuse (SessionChanged,
            // no POST) when it no longer matches the account the caller resolved and decided to write
            // under. This INCLUDES the active session now being null (logged out) while the caller had
            // one — the contract is « active != expected ⇒ SessionChanged », checked before any other
            // not-found mapping.
            val active = activePseudo()
            if (expectedPseudo != null && active != expectedPseudo) {
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "writeBackFlag: active account changed since caller's check → no write",
                )
                return@withContext MpStorageWriteResult.SessionChanged
            }
            // No expected pseudo (manual / dev path) and no active session → nothing to write under.
            val owner = active
                ?: return@withContext MpStorageWriteResult.TargetNotFound

            // 4. Locate + GET the edit form and parse the document.
            val location = locateForWrite(owner)
                ?: return@withContext MpStorageWriteResult.TargetNotFound
            val read = readFormAndDocument(location)
                ?: return@withContext MpStorageWriteResult.TargetNotFound
            // 6. Refuse an unreadable document — never repaired (ADR-014 §3).
            val document = read.document
                ?: return@withContext MpStorageWriteResult.Unreadable

            // 5. Verbatim backup (the raw content_form BEFORE any mutation) for the restore path.
            val backup = read.form.initialContent

            // 7-9. Pure read-modify-write + skip / no-op short-circuit + cap.
            when (val outcome = envelopeWriter.upsertFlag(document.rawEnvelope, entry, updateOnly = updateOnly)) {
                MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope ->
                    // Defensive : the parser already accepted it, so this should not happen — but if the
                    // raw text is somehow not a JSON object we surface it, never overwrite (ADR-014 §3).
                    MpStorageWriteResult.Unreadable

                is MpStorageEnvelopeWriter.Outcome.SkippedNotPresent -> {
                    // #597 UPDATE-ONLY : the threadId is not already tracked → never add it (anti-pollution
                    // of the shared document). No POST; the document is left byte-fidèle.
                    diagnostics.record(
                        DiagnosticsLog.Level.INFO,
                        LOG_TAG,
                        "writeBackFlag: skipped (threadId not present, update-only)",
                    )
                    MpStorageWriteResult.SkippedNotPresent
                }

                is MpStorageEnvelopeWriter.Outcome.NoOp -> {
                    // 8. The target position did not change : success WITHOUT a POST (the document is
                    // left byte-fidèle ; writing an identical body would needlessly bump lastUpdate).
                    diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "writeBackFlag: no-op (position unchanged)")
                    MpStorageWriteResult.Success(verified = true)
                }

                is MpStorageEnvelopeWriter.Outcome.TooLarge -> {
                    val cap = MpStorageRepository.MAX_CONTENT_FORM_BYTES
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG,
                        "writeBackFlag oversize: ${outcome.sizeBytes} bytes > $cap",
                    )
                    MpStorageWriteResult.TooLarge(outcome.sizeBytes)
                }

                MpStorageEnvelopeWriter.Outcome.UnsafeContent -> {
                    // FAIL-CLOSED (C4) : the mutated body carries a non-BMP / lone-surrogate code point
                    // HFR would truncate at — POSTing it would corrupt the shared third-party document.
                    // Refuse the write (no POST) ; the document is left byte-fidèle (never stripped).
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG,
                        "writeBackFlag UNSAFE content (non-BMP / surrogate) → no write",
                    )
                    MpStorageWriteResult.UnsafeContent
                }

                is MpStorageEnvelopeWriter.Outcome.Mutated ->
                    postAndVerify(location, read.form, owner, mutatedBody = outcome.body, backupBody = backup)
            }
        }
    }

    override suspend fun previewWriteBackFlag(entry: MpStorageFlagEntry): MpStorageWritePreview {
        return withContext(ioDispatcher) {
            val owner = activePseudo() ?: return@withContext MpStorageWritePreview.TargetNotFound
            val location = locateForWrite(owner) ?: return@withContext MpStorageWritePreview.TargetNotFound
            val read = readFormAndDocument(location) ?: return@withContext MpStorageWritePreview.TargetNotFound
            val document = read.document ?: return@withContext MpStorageWritePreview.Unreadable

            // Preview is the MANUAL path : upsert add-or-update (updateOnly = false), so SkippedNotPresent
            // is never produced here — mapped to Prepared(verbatim) for an exhaustive `when` regardless.
            when (val outcome = envelopeWriter.upsertFlag(document.rawEnvelope, entry)) {
                MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope -> MpStorageWritePreview.Unreadable
                is MpStorageEnvelopeWriter.Outcome.SkippedNotPresent -> MpStorageWritePreview.Prepared(outcome.body)
                is MpStorageEnvelopeWriter.Outcome.NoOp -> MpStorageWritePreview.Prepared(outcome.body)
                is MpStorageEnvelopeWriter.Outcome.TooLarge -> MpStorageWritePreview.TooLarge(outcome.sizeBytes)
                MpStorageEnvelopeWriter.Outcome.UnsafeContent -> MpStorageWritePreview.UnsafeContent
                is MpStorageEnvelopeWriter.Outcome.Mutated -> MpStorageWritePreview.Prepared(outcome.body)
            }
        }
    }

    /**
     * 10-15. POSTs the mutated body, RE-READS the first post, and either confirms the write
     * ([MpStorageWriteResult.Success]) or restores the [backupBody] and re-reads — surfacing
     * [MpStorageWriteResult.VerificationFailedRestored] when the backup is back in place, or
     * [MpStorageWriteResult.VerificationFailedRestoreFailed] (critical) when it could not be restored.
     *
     * Both POSTs use the same guarded builder (active pseudo + CONSTANT subject hash). The re-read
     * comparison is on the raw `content_form` string : HFR may re-serialise, but the de-facto contract
     * stores the textarea verbatim, so an exact match is the safe acceptance signal (NOT OBSERVED LIVE).
     */
    @Suppress("ReturnCount") // Guard (wrong subject / session) + verified-OK early return + the restore tail.
    private suspend fun postAndVerify(
        location: MpStorageLocation,
        form: ReplyForm,
        pseudo: String,
        mutatedBody: String,
        backupBody: String,
    ): MpStorageWriteResult {
        // IDENTITY GUARD (C2, Codex hardening) — the locate + GET above suspend ; re-resolve the active
        // pseudo IMMEDIATELY before the POST and abort if the account switched in between, so neither
        // the POST nor the restore can run under a different session than the one that read the document.
        if (activePseudo() != pseudo) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "writeBackFlag: active account changed before POST → no write",
            )
            return MpStorageWriteResult.SessionChanged
        }

        val postBody = buildPrivateMessageEditBody(location, form, pseudo, mutatedBody)
            ?: return MpStorageWriteResult.TargetNotFound // wrong subject → never POST (structural guard)

        hfrClient.submitPrivateMessageEdit(postBody)
        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "writeBackFlag POSTed → verifying")

        // IDENTITY GUARD (C2) — the POST suspends ; re-check before the verify re-read so the GET does
        // not run under a switched session (it would re-read the wrong account's document with this
        // thread's id and could mislabel the result). The write itself already happened under the right
        // session ; if the account changed now, surface it loud rather than verify against the wrong one.
        if (activePseudo() != pseudo) {
            diagnostics.record(
                DiagnosticsLog.Level.ERROR,
                LOG_TAG,
                "writeBackFlag: active account changed after POST, before verify → unverifiable",
            )
            return MpStorageWriteResult.VerificationFailedRestoreFailed(
                mutatedBody.toByteArray(Charsets.UTF_8).size,
                actualBytes = 0,
            )
        }

        val readBack = reReadContentForm(location)
        if (readBack == mutatedBody) {
            diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "writeBackFlag verified OK")
            return MpStorageWriteResult.Success(verified = true)
        }

        // Not byte-identical. Tell a HEALTHY document apart from a CORRUPTED one before deciding to
        // restore (Codex review): a valid JSON envelope that merely differs means HFR re-encoded
        // entities OR a concurrent client (DTCloud) wrote between our GET and this re-GET — restoring
        // the backup there would CLOBBER a legitimate last-write-wins update. Only a null / non-JSON
        // read-back is real corruption (the HFR non-UTF-8 truncation, #114) → that is what we restore.
        if (readBack != null && envelopeWriter.isJsonEnvelope(readBack)) {
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "writeBackFlag: valid-but-different read-back (re-encoding / concurrent) — kept, not restored",
            )
            return MpStorageWriteResult.Success(verified = false)
        }

        val expectedBytes = mutatedBody.toByteArray(Charsets.UTF_8).size
        val actualBytes = readBack?.toByteArray(Charsets.UTF_8)?.size ?: 0
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG,
            "writeBackFlag verify CORRUPTION (expected=$expectedBytes actual=$actualBytes) → restoring backup",
        )
        return restoreBackup(location, form, pseudo, backupBody, expectedBytes, actualBytes)
    }

    // ReturnCount: the session guard (C2), the build-failure guard, and the restored/failed outcome are
    // three legitimate exits; splitting would obscure the restore's linear flow.
    @Suppress("ReturnCount")
    private suspend fun restoreBackup(
        location: MpStorageLocation,
        form: ReplyForm,
        pseudo: String,
        backupBody: String,
        expectedBytes: Int,
        actualBytes: Int,
    ): MpStorageWriteResult {
        // IDENTITY GUARD (C2) — the verify GET above suspended ; re-check before the restore POST so a
        // mid-operation account switch can never POST the restore under a different session (it would
        // hit the new account's storage). Declining is the lesser evil : we surface a possibly
        // un-restored document (loud) rather than write it to the wrong place.
        if (activePseudo() != pseudo) {
            diagnostics.record(
                DiagnosticsLog.Level.ERROR,
                LOG_TAG,
                "writeBackFlag: active account changed before restore → no restore POST (document may be inconsistent)",
            )
            return MpStorageWriteResult.VerificationFailedRestoreFailed(expectedBytes, actualBytes)
        }

        // Reuse the initial edit form: HFR's hash_check is session-stable (verified live, NOT rotated
        // per-request), so the original form is valid for the restore. The guarded builder still
        // refuses if its subject is not the storage hash.
        val restoreBody = buildPrivateMessageEditBody(location, form, pseudo, backupBody)
            ?: return MpStorageWriteResult.VerificationFailedRestoreFailed(expectedBytes, actualBytes)

        hfrClient.submitPrivateMessageEdit(restoreBody)
        val restored = reReadContentForm(location)
        return if (restored == backupBody) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "writeBackFlag restored backup OK (write not applied)",
            )
            MpStorageWriteResult.VerificationFailedRestored(expectedBytes, actualBytes)
        } else {
            // CRITICAL : we could not bring the document back to its backup. Log loudly.
            diagnostics.record(
                DiagnosticsLog.Level.ERROR,
                LOG_TAG,
                "writeBackFlag RESTORE FAILED — storage may be inconsistent",
            )
            MpStorageWriteResult.VerificationFailedRestoreFailed(expectedBytes, actualBytes)
        }
    }

    /** Re-fetches the full edit [ReplyForm] of the first post at [location] (null when unreadable). */
    private suspend fun reReadForm(location: MpStorageLocation): ReplyForm? =
        replyFormParser
            .parse(hfrClient.getPrivateMessageEditForm(location.threadId, location.numreponse))
            .getOrNull()
            ?.takeUnless { it.isAnonymous }

    /** Re-reads the raw `content_form` of the first post at [location] for verification (null when unreadable). */
    private suspend fun reReadContentForm(location: MpStorageLocation): String? =
        reReadForm(location)?.initialContent

    /**
     * The `bdd.php cat=prive` POST body for the storage first post. TWO deliberate differences from
     * [fr.forumhfr.redface2.core.data.write.DefaultEditPostRepository]'s edit body :
     *  - `cat` is the String `"prive"` (the storage post lives under the private-message category) ;
     *  - `content_form` is the mutated/backup JSON document, not user BBCode.
     *
     * SECURITY / CORRECTNESS guards (ADR-014 §4) :
     *  - `sujet` is ALWAYS the CONSTANT [HfrConstants.MP_STORAGE_SUBJECT_HASH], NEVER `form.sujet` ;
     *  - returns `null` (REFUSE TO POST) when the parsed [form]'s subject does not equal that constant —
     *    a structural guard against writing into the wrong conversation ;
     *  - `pseudo` is the ACTIVE account's pseudo, sent verbatim ;
     *  - `password` and `delete` are hard-denied (never resend the deletion checkbox).
     */
    private fun buildPrivateMessageEditBody(
        location: MpStorageLocation,
        form: ReplyForm,
        pseudo: String,
        contentForm: String,
    ): FormBody? {
        // Structural guard : never POST into a conversation whose subject is not the storage hash.
        if (form.sujet != HfrConstants.MP_STORAGE_SUBJECT_HASH) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "writeBackFlag ABORT: form subject is not the storage hash → no POST",
            )
            return null
        }

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
            put("pseudo", pseudo)
            // ALWAYS the constant hash — never form.sujet (the guard above also enforces they match).
            put("sujet", HfrConstants.MP_STORAGE_SUBJECT_HASH)
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
     * [ReplyForm] (for hash_check / sujet / hidden fields / the verbatim content_form) plus the parsed
     * [MpStorageDocument] (null when the body is not a readable v0.1 envelope — surfaced as unreadable,
     * never repaired). Returns `null` when the edit form itself cannot be obtained (stale location).
     * Throws [SessionExpiredException] on an anonymous composer.
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

    private suspend fun syncWriteEnabled(): Boolean =
        userPreferencesRepository.observeSyncPrivateMessagesWriteEnabled().first()

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
