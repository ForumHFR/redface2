package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftOffer
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ED-161/170: serialize autosaves and attempts; a stale writer never replaces a durable attempt. */
class EditorDraftSession(private val store: EditorDraftStore, private val key: String?) {
    private val writes = Mutex()
    var owner: String? = null
        private set
    private var version = 0L
    var offers: List<DraftOffer> = emptyList()
        private set
    private var protectedOffer: DraftOffer? = null
    private var offersLoaded = false
    private val resolvedOffers = mutableSetOf<String>()
    private var lastBody = ""
    /** Text Undo/Redo can return to the last restore after an intervening autosave lost its offsets. */
    private var restoredBody: EditorBodyMerge? = null
    var bodyProvenance: EditorBodyProvenance? = null
        private set
    private var writingVersion: Long? = null
    /** ED-81: includes a CAS committed on the store dispatcher whose result is still in flight. */
    val nextContentVersion: Long get() = (writingVersion ?: version) + 1
    /** Optional surface checkpoint; null result announces the revision before the first suspension. */
    var checkpointObserver: ((Draft, Long, StoreWriteResult?) -> Unit)? = null
    private var published = false
    private var readable = false
    private var unresolved: AttemptToken? = null
    var pendingInlineQuoteRequest: PendingInlineQuoteRequest? = null
        private set
    var hasUnreadableInlineQuoteRequest = false
        private set
    private var unreadableVersion: Long? = null
    var invalidated = false
        private set

    @Suppress("ComplexCondition") // Eligibility requires reply scope, ordinary content and absence of quote-only proof.
    suspend fun load(): DraftEntry? {
        readable = false
        offersLoaded = false
        resolvedOffers.clear()
        restoredBody = null
        protectedOffer = null
        offers = emptyList()
        hasUnreadableInlineQuoteRequest = false
        unreadableVersion = null
        val entry = try {
            owner = store.currentOwner()
            key?.let { store.loadEntry(owner, it) }
        } catch (error: UnreadableInlineQuoteRequestException) {
            // Only an unreadable inline request seals the session until a reload.
            readable = false
            hasUnreadableInlineQuoteRequest = true
            unreadableVersion = error.contentVersion
            throw error
        } catch (error: IOException) {
            // Preserve the ordinary best-effort restore contract. Versioned writes still guard the row.
            readable = true
            throw error
        }
        val composition = entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
        lastBody = composition?.draft?.body.orEmpty()
        bodyProvenance = composition?.draft?.bodyProvenance
        version = entry?.version ?: 0L
        published = entry?.attempt?.status == AttemptStatus.PUBLISHED
        unresolved = entry?.attempt?.token?.takeUnless { published }
        pendingInlineQuoteRequest = entry?.draft?.pendingInlineQuoteRequest.takeUnless { published }
        // Offer reads are best effort too: keep the loaded revision writable and let the store
        // protect the current row when offersLoaded remains false after an IOException.
        readable = true
        offers = key?.let { store.loadOffers(owner, it) }.orEmpty()
        offersLoaded = true
        val eligible = entry?.takeIf {
            it.attempt == null && it.draft.body.isNotBlank() &&
                bodyProvenance?.isQuoteOnly(it.draft.body) != true &&
                (key?.startsWith("reply:") == true || key?.startsWith("mpreply:") == true)
        }
        protectedOffer = eligible?.let { candidate ->
            offers.firstOrNull { it.draft.restoreOfferFingerprint() == candidate.draft.restoreOfferFingerprint() &&
                it.draft.updatedAt == candidate.draft.updatedAt
            } ?: DraftOffer.from(candidate)
        }
        return entry
    }

    fun invalidate() { invalidated = true }

    suspend fun ownsCurrentAccount(): Boolean {
        val current = store.currentOwner()
        return !invalidated && owner != null && current == owner
    }

    suspend fun save(draft: Draft): StoreWriteResult = writes.withLock {
        // Once a storage call can commit, cancellation must not separate its result from adoption.
        withContext(NonCancellable) {
            val result = if (canWrite()) checkpointWrite(ownedDraft(draft)) {
                writeComposition(ownedDraft(draft))
            } else StoreWriteResult.OwnerChanged
            if (result is StoreWriteResult.Written) adoptBody(ownedDraft(draft))
            adopt(result)
            result
        }
    }

    /** A certified resume is C, not a new offered D. Existing durable offers remain untouched. */
    fun resumeContent() { protectedOffer = null }

    /** Fresh opening only, after restoration declined C; shared handoffs/recreations keep C. */
    suspend fun discardQuoteOnly(): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val result = mutation { store.discardQuoteOnly(owner, checkNotNull(key), version) }
            if (result is StoreWriteResult.Written) {
                lastBody = ""
                bodyProvenance = null
                protectedOffer = null
                restoredBody = null
            }
            adopt(result)
            result
        }
    }

    /** Resolve the legacy single-offer UI to a stable version, including the not-yet-copied source. */
    fun offered(body: String): DraftOffer? =
        (listOfNotNull(protectedOffer) + offers).firstOrNull { it.draft.body == body }

    /** C plus consumption is one CAS; failures retain the offer and never replay a merge. */
    suspend fun resolveOffer(offer: DraftOffer, draft: Draft): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val snapshot = ownedDraft(draft)
            val result = checkpointWrite(snapshot) {
                mutation { store.resolveOffer(owner, checkNotNull(key), offer, version, protected(snapshot)) }
            }
            if (result is StoreWriteResult.Written) {
                adoptResolvedOffer(offer, snapshot)
                adoptBody(snapshot)
            }
            adopt(result)
            result
        }
    }

    /** A new user action only; loading/restoring a surface must never enqueue its launch snapshot. */
    suspend fun enqueueInlineQuoteRequest(request: PendingInlineQuoteRequest): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val result = mutation {
                store.enqueueInlineQuoteRequest(owner, checkNotNull(key), request, version)
            }
            if (result is StoreWriteResult.Written) {
                val reply = key?.startsWith("reply:") == true || key?.startsWith("mpreply:") == true
                if (reply && unresolved == null && bodyProvenance?.isQuoteOnly(lastBody) == true) {
                    lastBody = ""
                    bodyProvenance = null
                    restoredBody = null
                }
                pendingInlineQuoteRequest = pendingInlineQuoteRequest?.merge(request) ?: request
            }
            adopt(result)
            result
        }
    }

    /**
     * Await delivery ([draft] supplied) or ignore (no draft). The exact request guards GET callbacks
     * that finish after a newer quote action. A dormant request can only be ignored, not delivered.
     */
    suspend fun consumeInlineQuoteRequest(
        request: PendingInlineQuoteRequest, draft: Draft? = null, deliveredBody: String? = null,
    ): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val snapshot = draft?.let(::ownedDraft)?.let {
                if (deliveredBody == null) it else it.copy(bodyProvenance =
                    (it.bodyProvenance ?: EditorBodyProvenance()).delivered(request.scope, deliveredBody, it.body))
            }
            val unresolvedOffer = snapshot?.offerToResolve?.id?.let { it !in resolvedOffers } == true
            val deliveryBlocked = draft != null && unresolved != null
            val result = if (pendingInlineQuoteRequest != request || deliveryBlocked || unresolvedOffer) {
                StoreWriteResult.Conflict
            } else {
                checkpointWrite(snapshot) {
                    mutation { store.consumeInlineQuoteRequest(owner, checkNotNull(key), request, version,
                        snapshot?.let(::protected)) }
                }
            }
            if (result is StoreWriteResult.Written) {
                pendingInlineQuoteRequest = null
                if (snapshot != null) adoptBody(snapshot)
            }
            adopt(result)
            result
        }
    }

    /** The raw row stays sealed until this explicit abandonment succeeds and the binding reloads. */
    @Suppress("ComplexCondition") // One guard: sealed flag, live binding, key, and the version found unreadable.
    suspend fun discardUnreadableInlineQuoteRequest(): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val expectedVersion = unreadableVersion
            if (!hasUnreadableInlineQuoteRequest || invalidated || key == null || expectedVersion == null) {
                StoreWriteResult.Conflict
            } else {
                try {
                    store.discardUnreadableInlineQuoteRequest(owner, key, expectedVersion)
                } catch (error: IOException) {
                    StoreWriteResult.Failed(error)
                }
            }
        }
    }

    suspend fun begin(draft: Draft, attemptId: String): BeginAttemptResult = writes.withLock {
        if (!canWrite()) return@withLock BeginAttemptResult.Rejected(StoreWriteResult.OwnerChanged)
        val snapshot = ownedDraft(draft)
        val needsResolution = snapshot.offerToResolve?.id?.let { it !in resolvedOffers } == true
        val prepared = if (published || needsResolution) {
            writeComposition(snapshot)
        } else StoreWriteResult.Written(version)
        adopt(prepared)
        if (prepared !is StoreWriteResult.Written) return@withLock BeginAttemptResult.Rejected(prepared)
        store.beginAttempt(owner, checkNotNull(key), protected(snapshot), version, attemptId).also { result ->
            if (result is BeginAttemptResult.Started) {
                version = result.token.version
                unresolved = result.token
                adoptBody(snapshot)
            }
        }
    }

    suspend fun finish(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult = writes.withLock {
        store.finishAttempt(token, outcome).also { result ->
            adopt(result)
            if (result is StoreWriteResult.Written) {
                published = outcome == AttemptOutcome.PUBLISHED
                unresolved = token.takeIf { outcome == AttemptOutcome.UNCERTAIN }
                if (published) {
                    pendingInlineQuoteRequest = null
                    bodyProvenance = null
                    lastBody = ""
                    protectedOffer = null
                    restoredBody = null
                }
            }
        }
    }

    suspend fun erase(token: AttemptToken): StoreWriteResult = writes.withLock {
        store.erasePublishedContent(token)
    }

    private fun canWrite(): Boolean = readable && !invalidated && key != null

    private suspend fun checkpointWrite(draft: Draft?, write: suspend () -> StoreWriteResult): StoreWriteResult {
        val target = version + 1
        writingVersion = target
        if (draft != null) checkpointObserver?.invoke(draft, target, null)
        return try {
            write().also { result ->
                if (draft != null) checkpointObserver?.invoke(draft, target, result)
            }
        } finally {
            writingVersion = null
        }
    }

    private fun ownedDraft(draft: Draft): Draft = draft.copy(
        pendingInlineQuoteRequest = pendingInlineQuoteRequest,
        bodyProvenance = draft.bodyProvenance ?: bodyProvenance?.let { provenance ->
            val restored = restoredBody?.takeIf { it.body == draft.body }?.provenance
            if (restored == null) provenance.rebase(lastBody, draft.body)
            // Reuse only the boundaries. A later delivery remains delivered even if its text was undone.
            else provenance.copy(draftEnd = restored.draftEnd, quotesEnd = restored.quotesEnd)
        },
    )

    private fun protected(draft: Draft): Draft = draft.copy(
        offerToPreserve = protectedOffer, protectCurrent = !offersLoaded,
    )

    private fun adoptBody(draft: Draft) {
        lastBody = draft.body
        bodyProvenance = draft.bodyProvenance
    }

    private suspend fun mutation(operation: suspend () -> StoreWriteResult): StoreWriteResult = try {
        if (canWrite()) operation() else StoreWriteResult.OwnerChanged
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private suspend fun writeComposition(snapshot: Draft): StoreWriteResult = try {
        val draft = protected(snapshot).copy(offerToResolve = null)
        val offer = snapshot.offerToResolve?.takeUnless { it.id in resolvedOffers }
        val attempt = unresolved
        when {
            offer != null -> store.resolveOffer(owner, checkNotNull(key), offer, version, draft).also {
                if (it is StoreWriteResult.Written) adoptResolvedOffer(offer, snapshot)
            }
            published -> store.startNewComposition(owner, checkNotNull(key), draft, version)
            attempt != null -> store.saveUnresolved(attempt, draft, version)
            else -> store.saveIfVersion(owner, checkNotNull(key), draft, version)
        }
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private fun adoptResolvedOffer(offer: DraftOffer, draft: Draft) {
        resolvedOffers += offer.id
        offers = offers.filterNot { it.id == offer.id }
        if (protectedOffer?.id == offer.id) protectedOffer = null
        draft.bodyProvenance?.takeIf { it.draftEnd > 0 }?.let {
            restoredBody = EditorBodyMerge(draft.body, it)
        }
    }

    private fun adopt(result: StoreWriteResult) {
        if (result is StoreWriteResult.Written) {
            version = result.version
            published = false
        }
    }
}
