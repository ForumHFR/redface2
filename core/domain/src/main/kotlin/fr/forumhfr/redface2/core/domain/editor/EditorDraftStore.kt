package fr.forumhfr.redface2.core.domain.editor

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Per-account local cache of in-progress editor content (#405).
 *
 * Drafts are bound to the account that OWNED the editor session, not to whatever account happens
 * to be active when a (debounced) write lands. The caller snapshots [currentOwner] once when the
 * editor opens and threads it through every [load]/[save]/[delete] of that session. This closes a
 * cross-account leak: a logout or account switch between opening the editor and a late autosave
 * could otherwise write account A's body/recipients under account B's row key — B would then read
 * A's private draft, and A's logout purge (keyed by A) would miss it.
 *
 * [load]/[save]/[delete] take a stable, content-free context [key] built by [EditorDraftKey]; it
 * never embeds message bodies, subjects or recipients. MP drafts ([Draft.isPrivate] == true) are
 * stripped of content on logout/account switch (CacheInvalidator), retaining only durable attempts;
 * all drafts older than the retention TTL are pruned on app start. No draft content is ever logged
 * or surfaced in diagnostics.
 */
interface EditorDraftStore {

    /**
     * Snapshot of the account that owns drafts written right now (lowercased pseudo), or null when
     * anonymous. Capture this ONCE when the editor opens and pass it back to [load]/[save]/[delete];
     * a later account change then cannot attribute this session's content to a different account.
     */
    suspend fun currentOwner(): String?

    /** One-shot read of [owner]'s draft under [key], or null (incl. when [owner] is null). */
    suspend fun load(owner: String?, key: String): Draft?

    /**
     * Legacy autosave: upserts ordinary drafts, but cannot overwrite any durable attempt.
     * Stamps `updatedAt` from the clock. No-op when
     * [owner] is null (anonymous session) OR when [owner] is no longer the active account: the
     * draft belongs to a session whose account has switched or logged out, so persisting it would
     * either leak it to the new account or revive a row the logout purge already swept.
     * [Draft.body]/[Draft.subject]/[Draft.recipients] are the raw editor fields; [Draft.isPrivate]
     * marks MP drafts for the logout purge.
     */
    suspend fun save(owner: String?, key: String, draft: Draft)

    /** Legacy best-effort deletion. New sends must use [erasePublishedContent], which reports failures. */
    suspend fun delete(owner: String?, key: String)

    /** ED-170: read content and its durable outcome together, before any restoration. */
    suspend fun loadEntry(owner: String?, key: String): DraftEntry?

    /** ED-161/170: CAS autosave; unresolved or published attempts reject ordinary saves. */
    suspend fun saveIfVersion(owner: String?, key: String, draft: Draft, expectedVersion: Long): StoreWriteResult

    /** ED-170: edit unresolved content by CAS without acknowledging or replacing its attempt identity. */
    suspend fun saveUnresolved(token: AttemptToken, draft: Draft, expectedVersion: Long): StoreWriteResult

    /** ED-161: explicitly start a new composition after publication, never by a late autosave. */
    suspend fun startNewComposition(owner: String?, key: String, draft: Draft, expectedVersion: Long): StoreWriteResult

    /**
     * #1553: persist a new explicit quote action before opening an editor. Owner/version checked;
     * merge with an ordinary draft, preserving user content. An intact quote-only reply is cleared
     * first (ED-121: new Cite B gives B alone), without touching offers. A published row starts an
     * empty composition; an unresolved attempt keeps its exact identity/status and a dormant request.
     * No saved-instance-state restoration may call this mutation to replay old coordinates.
     */
    suspend fun enqueueInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult

    /**
     * #1553: CAS removal of exactly [request]. With [draft], atomically deliver the reconciled body
     * and remove the request; unresolved attempts reject delivery. Without [draft], ignore only the
     * request, retaining all fields and attempt metadata. Failures leave the entire row unchanged.
     */
    suspend fun consumeInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long, draft: Draft? = null,
    ): StoreWriteResult

    /** ED-121: offers are read independently of C's publication status, newest first. */
    suspend fun loadOffers(owner: String?, key: String): List<DraftOffer>

    /**
     * ED-121: a fresh reply opening durably drops an intact quote-only body and its provenance.
     * CAS-guarded; preserves offers, pending requests and headers, and rejects every attempt.
     * Keep the empty revision so a stale writer or a later Bundle cannot resurrect the old quote.
     */
    suspend fun discardQuoteOnly(owner: String?, key: String, expectedVersion: Long): StoreWriteResult

    /** ED-121: write live C and consume exactly this offer in the same transaction (also for Ignore). */
    suspend fun resolveOffer(
        owner: String?, key: String, offer: DraftOffer, expectedVersion: Long, draft: Draft,
    ): StoreWriteResult

    /** Clear only the request of the revision observed by the unreadable load; otherwise reload. */
    suspend fun discardUnreadableInlineQuoteRequest(
        owner: String?, key: String, expectedVersion: Long,
    ): StoreWriteResult

    /**
     * ED-170: atomically store the complete snapshot and IN_PROGRESS before issuing any POST.
     * Every accepted invocation supplies a fresh [attemptId], including after rejection;
     * the default keeps direct callers independent of any previously attempted identity.
     */
    suspend fun beginAttempt(
        owner: String?,
        key: String,
        snapshot: Draft,
        expectedVersion: Long,
        attemptId: String = UUID.randomUUID().toString(),
    ): BeginAttemptResult

    /**
     * ED-170: compare identity/version; certain failure clears only the attempt, even after an account
     * switch. This metadata-only resolution never exposes the old owner's content to the new account.
     */
    suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult

    /** ED-161: erase only a matching published attempt's content, preserving the durable marker. */
    suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult

    /** ED-170: migrated drafts start at version 0, also used for absent rows when writing. */
    data class DraftEntry(val draft: Draft, val version: Long, val attempt: Attempt? = null)

    /** Identity includes the source revision and content; occupied offers are never replaced. */
    data class DraftOffer(val id: String, val sourceVersion: Long, val draft: Draft) {
        companion object {
            fun from(entry: DraftEntry): DraftOffer = DraftOffer(
                "${entry.version}:${entry.draft.updatedAt}:${entry.draft.restoreOfferFingerprint()}", entry.version,
                entry.draft.copy(
                    pendingInlineQuoteRequest = null, offerToPreserve = null,
                    offerToResolve = null, protectCurrent = true,
                ),
            )
        }
    }

    /** ED-170: identity bound to an account, context and initial revision; later edits keep this version. */
    data class AttemptToken(val owner: String, val key: String, val id: String, val version: Long)

    /** ED-170: one durable attempt and the outcome currently known for it. */
    data class Attempt(val token: AttemptToken, val status: AttemptStatus)

    /** ED-170: nullable attempt is the only ordinary-draft representation. */
    enum class AttemptStatus { IN_PROGRESS, PUBLISHED, UNCERTAIN }

    /** ED-161/170: certain failure clears metadata; publication retains a tombstone. */
    enum class AttemptOutcome { PUBLISHED, CERTAIN_FAILURE, UNCERTAIN }

    /** ED-170: no-op and failed writes can never authorize a POST. */
    sealed interface StoreWriteResult {
        data class Written(val version: Long) : StoreWriteResult
        data object OwnerChanged : StoreWriteResult
        data object Conflict : StoreWriteResult
        data class Failed(val cause: IOException) : StoreWriteResult
    }

    /** ED-170: only Started carries permission to continue to the network pipeline. */
    sealed interface BeginAttemptResult {
        data class Started(val token: AttemptToken) : BeginAttemptResult
        data class Rejected(val reason: StoreWriteResult) : BeginAttemptResult
    }

    data class Draft(
        val body: String,
        val subject: String? = null,
        val recipients: String? = null,
        val isPrivate: Boolean = false,
        /** Epoch millis of last write; set by the store, ignored on save input. */
        val updatedAt: Long = 0L,
        /** #1553: owned by the draft session, never restored from a field or navigation snapshot. */
        val pendingInlineQuoteRequest: PendingInlineQuoteRequest? = null,
        /** ED-89/121: durable delivery proof and D | quotes | typing boundaries; never in a Bundle. */
        val bodyProvenance: EditorBodyProvenance? = null,
        /** Write-only session protection. The store copies it transactionally, never stores it in C. */
        val offerToPreserve: DraftOffer? = null,
        /** Session-only restore decision: a post-merge autosave also resolves D atomically. */
        val offerToResolve: DraftOffer? = null,
        /** Raw/legacy writers protect the current source by default; a loaded session decides explicitly. */
        val protectCurrent: Boolean = true,
    ) {
        /**
         * Content-only marker for the restore-offer guard (#1415). The full draft must not be put
         * in saved instance state, especially for private messages; hashing the length-delimited
         * fields also ignores [updatedAt], which changes without changing what the user would be
         * offered. Nullable fields use their editor representation (the empty string), so a Room
         * null-to-empty round-trip cannot make an identical offer look new.
         */
        fun restoreOfferFingerprint(): String {
            val canonical = buildString {
                append(body.length).append(':').append(body)
                val normalizedSubject = subject.orEmpty()
                append(normalizedSubject.length).append(':').append(normalizedSubject)
                val normalizedRecipients = recipients.orEmpty()
                append(normalizedRecipients.length).append(':').append(normalizedRecipients)
            }
            return MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    byte.toUByte().toString(radix = 16).padStart(length = 2, padChar = '0')
                }
        }
    }
}
