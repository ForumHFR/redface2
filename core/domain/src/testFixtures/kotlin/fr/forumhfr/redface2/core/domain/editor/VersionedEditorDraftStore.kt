package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftOffer
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.model.editor.EditorRecipientCodec
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/** ED-170: in-memory CAS fake, with observable durable boundaries; no feature or Room dependency. */
open class VersionedEditorDraftStore : EditorDraftStore {
    var activeOwner: String? = "tester"
    private val ownerEntries = mutableMapOf<String?, MutableMap<String, DraftEntry>>()
    private val ownerOffers = mutableMapOf<String?, MutableMap<String, MutableList<DraftOffer>>>()
    private fun offers(owner: String?, key: String) = ownerOffers.getOrPut(owner) { mutableMapOf() }
        .getOrPut(key) { mutableListOf() }
    val entries: MutableMap<String, DraftEntry> get() = ownerEntries.getOrPut(activeOwner) { mutableMapOf() }
    val saved: Map<String, Draft> get() = entries.mapValues { it.value.draft }
    val deletedKeys = mutableListOf<String>()
    var saveCount = 0
        private set
    var writes = 0
        private set
    var lastSavedOwner: String? = null
        private set
    var loadGate: CompletableDeferred<Unit>? = null
    var beginGate: CompletableDeferred<Unit>? = null
    var beginReturnGate: CompletableDeferred<Unit>? = null
    var saveReturnGate: CompletableDeferred<Unit>? = null
    var finishGate: CompletableDeferred<Unit>? = null
    var cleanupGate: CompletableDeferred<Unit>? = null
    var loadFailure: IOException? = null
    var loadOffersFailure: IOException? = null
    var saveFailure: IOException? = null
    var beginFailure: IOException? = null
    var cleanupFailure: IOException? = null
    var finishFailure: IOException? = null
    val attemptedIds = mutableListOf<String>()
    var beginCalls = 0
        private set
    var finishCalls = 0
        private set
    var loadCalls = 0
        private set

    fun preload(key: String, draft: Draft) { entries[key] = DraftEntry(draft, 0) }

    /** Mirrors Room's account-switch purge: only durable attempts survive, with no private content. */
    fun purgePrivateForUser(owner: String) {
        ownerOffers[owner.lowercase()]?.values?.forEach { offers -> offers.removeAll { it.draft.isPrivate } }
        val owned = ownerEntries[owner.lowercase()] ?: return
        for ((key, entry) in owned.toMap()) {
            if (entry.draft.isPrivate) {
                if (entry.attempt == null) {
                    owned.remove(key)
                    deletedKeys += key
                } else {
                    owned[key] = entry.copy(draft = entry.draft.copy(
                        body = "", subject = null, recipients = null,
                        pendingInlineQuoteRequest = null, bodyProvenance = null,
                    ))
                }
                writes++
            }
        }
    }

    override suspend fun currentOwner(): String? = activeOwner
    override suspend fun load(owner: String?, key: String): Draft? =
        loadEntry(owner, key)?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }?.draft
    override suspend fun loadEntry(owner: String?, key: String): DraftEntry? {
        loadCalls++
        loadFailure?.let {
            if (it is UnreadableInlineQuoteRequestException) {
                throw UnreadableInlineQuoteRequestException(entries[key]?.version, it)
            }
            throw it
        }
        loadGate?.await()
        return entries[key].takeIf { owner != null && owner == activeOwner }
    }
    override suspend fun loadOffers(owner: String?, key: String): List<DraftOffer> {
        loadOffersFailure?.let { throw it }
        return if (owner == null || owner != activeOwner) emptyList() else offers(owner, key)
            .sortedWith(compareByDescending<DraftOffer> { it.draft.updatedAt }.thenByDescending { it.sourceVersion })
    }

    fun preloadOffer(key: String, offer: DraftOffer) { offers(activeOwner, key).add(offer) }

    @Suppress("ReturnCount") // Explicit owner/failure/revision/eligibility guards mirror the Room CAS.
    override suspend fun discardQuoteOnly(owner: String?, key: String, expectedVersion: Long): StoreWriteResult {
        if (owner == null || owner != activeOwner) return StoreWriteResult.OwnerChanged
        saveFailure?.let { return StoreWriteResult.Failed(it) }
        val previous = entries[key] ?: return StoreWriteResult.Conflict
        if (previous.version != expectedVersion || !previous.isQuoteOnly(key)) return StoreWriteResult.Conflict
        entries[key] = previous.copy(draft = previous.draft.copy(body = "", bodyProvenance = null),
            version = expectedVersion + 1)
        writes++
        saveReturnGate?.await()
        return StoreWriteResult.Written(expectedVersion + 1)
    }

    private fun DraftEntry.isQuoteOnly(key: String): Boolean =
        attempt == null && EditorDraftKey.isReply(key) &&
            draft.bodyProvenance?.isQuoteOnly(draft.body) == true

    override suspend fun resolveOffer(
        owner: String?, key: String, offer: DraftOffer, expected: EditorDraftStore.Revision, draft: Draft,
    ): StoreWriteResult {
        if (owner == null || owner != activeOwner) return StoreWriteResult.OwnerChanged
        val previous = entries[key]
        return saveFailure?.let { StoreWriteResult.Failed(it) } ?: run {
            if (previous?.attempt?.status != expected.attemptStatus ||
                !canResolveOffer(owner, key, previous, offer, expected.contentVersion)
            ) {
                StoreWriteResult.Conflict
            } else {
                if (draft.offerToPreserve?.id != offer.id) protect(owner, key, previous, draft.offerToPreserve)
                entries[key] = DraftEntry(draft.copy(
                    offerToPreserve = null, offerToResolve = null, protectCurrent = true,
                    pendingInlineQuoteRequest = previous?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
                        ?.draft?.pendingInlineQuoteRequest,
                ), expected.contentVersion + 1, previous?.attempt?.takeUnless { it.status == AttemptStatus.PUBLISHED }
                    ?.copy(status = AttemptStatus.UNCERTAIN))
                offers(owner, key).removeAll { it.id == offer.id }
                saveCount++
                writes++
                lastSavedOwner = owner
                saveReturnGate?.await()
                StoreWriteResult.Written(expected.contentVersion + 1)
            }
        }
    }

    private fun canResolveOffer(
        owner: String, key: String, previous: DraftEntry?, offer: DraftOffer, expectedVersion: Long,
    ): Boolean {
        val source = previous?.takeIf { it.attempt == null }?.let {
            it.version == offer.sourceVersion &&
                it.draft.restoreOfferFingerprint() == offer.draft.restoreOfferFingerprint()
        } == true
        return (previous?.version ?: 0L) == expectedVersion && (source || offer in offers(owner, key))
    }

    @Suppress("ComplexCondition") // Mirrors the exact-source Room transaction guard.
    private fun protect(owner: String?, key: String, previous: DraftEntry?, offer: DraftOffer?) {
        if (previous != null && offer != null && previous.version == offer.sourceVersion &&
            previous.attempt == null &&
            previous.draft.restoreOfferFingerprint() == offer.draft.restoreOfferFingerprint() &&
            offers(owner, key).none { it.id == offer.id }
        ) offers(owner, key).add(offer.copy(draft = offer.draft.copy(
            offerToPreserve = null, offerToResolve = null, protectCurrent = true,
        )))
    }

    @Suppress("ComplexCondition") // Mirrors raw writer eligibility (ED-121 scope, ordinary content).
    private fun protection(key: String, entry: DraftEntry?, draft: Draft): DraftOffer? =
        draft.offerToPreserve ?: entry?.takeIf {
            draft.protectCurrent && it.attempt == null && it.draft.holdsOfferableContent(key) &&
                it.draft.bodyProvenance?.isQuoteOnly(it.draft.body) != true
        }?.let(DraftOffer::from)

    /** Mirrors Room: headers alone count on new compositions, with recipients decoded semantically. */
    private fun Draft.holdsOfferableContent(key: String): Boolean {
        val headers = !subject.isNullOrBlank() || EditorRecipientCodec.hasRecipients(recipients)
        return EditorDraftKey.keepsOfferedDrafts(key) &&
            (body.isNotBlank() || (EditorDraftKey.composesHeaders(key) && headers))
    }

    private fun offerCandidate(owner: String?, key: String, entry: DraftEntry?): DraftOffer? {
        val source = entry?.let { protection(key, it, it.draft.copy(offerToPreserve = null, protectCurrent = true)) }
        return source?.let { candidate ->
            offers(owner, key).firstOrNull {
                it.draft.restoreOfferFingerprint() == candidate.draft.restoreOfferFingerprint() &&
                    it.draft.updatedAt == candidate.draft.updatedAt
            } ?: candidate
        }
    }

    override suspend fun save(owner: String?, key: String, draft: Draft) {
        val entry = entries[key]
        saveIfVersion(owner, key, draft.copy(pendingInlineQuoteRequest = entry?.draft?.pendingInlineQuoteRequest),
            entry?.version ?: 0)
    }
    override suspend fun delete(owner: String?, key: String) {
        if (owner != null && owner == activeOwner && entries[key]?.attempt == null) {
            deletedKeys += key
            entries.remove(key)
            writes++
        }
    }
    override suspend fun saveIfVersion(
        owner: String?, key: String, draft: Draft, expectedVersion: Long,
    ): StoreWriteResult {
        val result = saveVersioned(owner, key, draft, expectedVersion, newComposition = false)
        saveReturnGate?.await() // The transaction committed, but its dispatcher has not delivered the result.
        return result
    }
    override suspend fun startNewComposition(owner: String?, key: String, draft: Draft, expectedVersion: Long) =
        saveVersioned(owner, key, draft, expectedVersion, newComposition = true)

    override suspend fun enqueueInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult {
        val entry = entries[key]
        val rejection = quoteMutationRejection(owner, key, request, expectedVersion)
        if (rejection != null) return rejection
        val composition = entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }?.let {
            if (it.isQuoteOnly(key)) it.copy(draft = it.draft.copy(body = "", bodyProvenance = null)) else it
        }
        val candidate = composition?.takeIf {
            it.draft.body.isNotBlank() && it.draft.bodyProvenance?.isQuoteOnly(it.draft.body) != true &&
                offers(owner, key).none { offer -> offer.draft.restoreOfferFingerprint() ==
                    it.draft.restoreOfferFingerprint() && offer.draft.updatedAt == it.draft.updatedAt }
        }?.let(DraftOffer::from)
        protect(owner, key, composition, candidate)
        val pending = composition?.draft?.pendingInlineQuoteRequest
        val draft = (composition?.draft ?: Draft(body = "", isPrivate = request.isPrivate)).copy(
            pendingInlineQuoteRequest = pending?.merge(request) ?: request,
        )
        entries[key] = DraftEntry(draft, expectedVersion + 1, composition?.attempt)
        writes++
        saveReturnGate?.await()
        return StoreWriteResult.Written(expectedVersion + 1)
    }

    @Suppress("LongParameterList") // Mirrors the store's request CAS and independent offer protection.
    override suspend fun consumeInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long, draft: Draft?,
        offerToPreserve: DraftOffer?, protectCurrent: Boolean,
    ): StoreWriteResult {
        val rejection = quoteMutationRejection(owner, key, request, expectedVersion)
        if (rejection != null) return rejection
        val entry = entries[key]
        val deliveryBlocked = draft != null && entry?.attempt != null
        // ED-121/132: a D merged into the delivered C is resolved in this same write, or nothing is.
        val resolved = draft?.offerToResolve
        val unavailable = resolvedOfferUnavailable(owner, key, entry, resolved, expectedVersion)
        return when {
            entry == null || unavailable -> StoreWriteResult.Conflict
            entry.draft.pendingInlineQuoteRequest != request || entry.attempt?.status == AttemptStatus.PUBLISHED ||
                deliveryBlocked -> StoreWriteResult.Conflict
            else -> {
                val candidate = if (draft != null) protection(key, entry, draft) else {
                    offerToPreserve ?: if (protectCurrent) offerCandidate(owner, key, entry) else null
                }
                settleConsumedOffers(owner, key, entry, candidate, resolved)
                entries[key] = entry.copy(
                    draft = (draft ?: entry.draft).copy(
                        pendingInlineQuoteRequest = null, offerToPreserve = null,
                        offerToResolve = null, protectCurrent = true,
                    ),
                    version = expectedVersion + 1,
                )
                writes++
                saveReturnGate?.await()
                StoreWriteResult.Written(expectedVersion + 1)
            }
        }
    }

    private fun resolvedOfferUnavailable(
        owner: String?, key: String, entry: DraftEntry?, resolved: DraftOffer?, expectedVersion: Long,
    ): Boolean = resolved != null && owner != null && !canResolveOffer(owner, key, entry, resolved, expectedVersion)

    /** Protect the replaced row unless it is the offer being resolved, then remove that offer. */
    private fun settleConsumedOffers(
        owner: String?, key: String, entry: DraftEntry, candidate: DraftOffer?, resolved: DraftOffer?,
    ) {
        if (candidate?.id != resolved?.id) protect(owner, key, entry, candidate)
        if (resolved != null) offers(owner, key).removeAll { it.id == resolved.id }
    }

    private fun quoteMutationRejection(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult? = when {
        owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
        saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
        request.key != key || (entries[key]?.version ?: 0L) != expectedVersion -> StoreWriteResult.Conflict
        else -> null
    }

    override suspend fun saveUnresolved(token: AttemptToken, draft: Draft, expectedVersion: Long): StoreWriteResult {
        val entry = entries[token.key]
        val result = when {
            saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
            token.owner != activeOwner -> StoreWriteResult.OwnerChanged
            entry?.version != expectedVersion || !entry.hasUnresolvedAttempt(token) -> StoreWriteResult.Conflict
            else -> {
                protect(token.owner, token.key, entry, protection(token.key, entry, draft))
                entries[token.key] = DraftEntry(
                    draft.copy(offerToPreserve = null, offerToResolve = null, protectCurrent = true),
                    expectedVersion + 1,
                    Attempt(token, AttemptStatus.UNCERTAIN),
                )
                saveCount++
                writes++
                lastSavedOwner = token.owner
                StoreWriteResult.Written(expectedVersion + 1)
            }
        }
        saveReturnGate?.await()
        return result
    }

    private fun saveVersioned(
        owner: String?, key: String, draft: Draft, expected: Long, newComposition: Boolean,
    ): StoreWriteResult {
        val entry = entries[key]
        val attempt = entry?.attempt
        val writable = attempt == null || newComposition && attempt.status == AttemptStatus.PUBLISHED
        return when {
            saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
            owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
            (entry?.version ?: 0) != expected || !writable -> StoreWriteResult.Conflict
            else -> {
                protect(owner, key, entry, protection(key, entry, draft))
                entries[key] = DraftEntry(
                    draft.copy(offerToPreserve = null, offerToResolve = null, protectCurrent = true), expected + 1,
                )
                saveCount++
                writes++
                lastSavedOwner = owner
                StoreWriteResult.Written(expected + 1)
            }
        }
    }

    @Suppress("ReturnCount") // Guards (owner / injected failure / row revision) + the written tail.
    override suspend fun discardUnreadableInlineQuoteRequest(
        owner: String?, key: String, expectedVersion: Long,
    ): StoreWriteResult {
        if (owner == null || owner != activeOwner) return StoreWriteResult.OwnerChanged
        saveFailure?.let { return StoreWriteResult.Failed(it) }
        val entry = entries[key] ?: return StoreWriteResult.Conflict
        if (entry.version != expectedVersion) return StoreWriteResult.Conflict
        entries[key] = entry.copy(
            draft = entry.draft.copy(pendingInlineQuoteRequest = null), version = entry.version + 1,
        )
        loadFailure = null
        writes++
        return StoreWriteResult.Written(entry.version + 1)
    }

    override suspend fun beginAttempt(
        owner: String?, key: String, snapshot: Draft, expectedVersion: Long, attemptId: String,
    ): BeginAttemptResult {
        beginCalls++
        attemptedIds += attemptId
        beginGate?.await()
        val failure = beginFailure
        val rejection = when {
            failure != null -> StoreWriteResult.Failed(failure)
            owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
            (entries[key]?.version ?: 0) != expectedVersion -> StoreWriteResult.Conflict
            entries[key]?.attempt != null -> StoreWriteResult.Conflict
            else -> null
        }
        if (rejection != null) return BeginAttemptResult.Rejected(rejection)
        val token = AttemptToken(checkNotNull(owner), key, attemptId, expectedVersion + 1)
        protect(owner, key, entries[key], protection(key, entries[key], snapshot))
        entries[key] = DraftEntry(
            snapshot.copy(offerToPreserve = null, offerToResolve = null, protectCurrent = true), token.version,
            Attempt(token, AttemptStatus.IN_PROGRESS),
        )
        writes++
        beginReturnGate?.await() // Owner was checked and the transaction committed before this suspension.
        return BeginAttemptResult.Started(token)
    }

    override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
        finishCalls++
        finishGate?.await()
        finishFailure?.let { return StoreWriteResult.Failed(it) }
        return if (token.owner != activeOwner && outcome != AttemptOutcome.CERTAIN_FAILURE) {
            StoreWriteResult.OwnerChanged
        } else {
            finishOwnedAttempt(token, outcome)
        }
    }

    private fun finishOwnedAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
        val owned = ownerEntries[token.owner]
        val entry = owned?.get(token.key)
        if (owned == null || entry == null || !entry.hasUnresolvedAttempt(token)) {
            return StoreWriteResult.Conflict
        }
        val status = when (outcome) {
            AttemptOutcome.CERTAIN_FAILURE -> null
            AttemptOutcome.PUBLISHED -> AttemptStatus.PUBLISHED
            AttemptOutcome.UNCERTAIN -> AttemptStatus.UNCERTAIN
        }
        owned[token.key] = entry.copy(
            attempt = status?.let { Attempt(token, it) },
            draft = if (status == AttemptStatus.PUBLISHED) entry.draft.copy(pendingInlineQuoteRequest = null)
            else entry.draft,
        )
        writes++
        return StoreWriteResult.Written(entry.version)
    }

    private fun DraftEntry?.hasUnresolvedAttempt(token: AttemptToken): Boolean =
        this?.attempt?.let { it.token == token && it.status != AttemptStatus.PUBLISHED } == true

    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult {
        cleanupGate?.await()
        if (token.owner != activeOwner) return StoreWriteResult.OwnerChanged
        val entry = entries[token.key]
        val failure = cleanupFailure
        return when {
            failure != null -> StoreWriteResult.Failed(failure)
            entry == null || entry.version != token.version ||
                entry.attempt != Attempt(token, AttemptStatus.PUBLISHED) -> StoreWriteResult.Conflict
            else -> {
                entries[token.key] = entry.copy(draft = Draft(body = "", isPrivate = entry.draft.isPrivate))
                writes++
                StoreWriteResult.Written(token.version)
            }
        }
    }
}
