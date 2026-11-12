package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult

/** Namespaces are independent of routing keys; Post retains its pre-S3 Bundle keys. */
enum class EditorSessionSurface(internal val namespace: String) {
    Post("postEditor"), TopicCreation("topicCreationEditor"), TopicFirstPost("topicFirstPostEditor"),
    MessageReply("messageReplyEditor"), MessageCompose("messageComposeEditor"),
}

enum class EditorSessionRestore { None, Offer, Resume, Uncertain }
enum class EditorSessionField { Body, Subject, Recipients, Subcategory }

/** D3: null wrapper means absent; a wrapper with a null id means an explicit empty selection. */
data class EditorSessionSubcategory(val id: Int?)

/** Content stays in the owning draft store; only its fingerprint and viewport enter the Bundle. */
data class EditorSessionCheckpoint(
    val draft: Draft,
    val version: Long,
    val selection: TextRange,
    val scroll: Int,
    val subcategory: EditorSessionSubcategory? = null,
)

/**
 * ED-81/86: VM-owned metadata checkpoint over EditorDraftSession's existing CAS observer.
 * Capture before suspension (also on ON_STOP); complete with the actual store result. A competing
 * writer's numeric revision alone never authorizes resume. No content, undo or chrome is serialized.
 * Surfaces supply a complete Draft (title in subject, chips/buffer via EditorRecipientState).
 */
class EditorSessionRestoration(private val saved: SavedStateHandle, surface: EditorSessionSurface) {
    private val prefix = surface.namespace
    private val ownerKey = "$prefix.resumeOwner"
    private val contextKey = "$prefix.resumeKey"
    private val versionKey = "$prefix.resumeVersion"
    private val startKey = "$prefix.selectionStart"
    private val endKey = "$prefix.selectionEnd"
    private val scrollKey = "$prefix.scroll"
    private val subcategoryKey = "$prefix.resumeSubcategory"
    val isRecreation = saved.contains(contextKey)
    private val previousOwner = saved.get<String>(ownerKey)
    private val previousKey = saved.get<String>(contextKey)
    private val previousVersion = saved.get<String>(versionKey)
    private val previousStart = saved.get<Int>(startKey) ?: 0
    private val previousEnd = saved.get<Int>(endKey) ?: 0
    private val previousScroll = saved.get<Int>(scrollKey) ?: 0
    private val previousSubcategory = savedSubcategory()
    private val decided = mutableSetOf<EditorSessionField>()
    private var resumed = false
    private var quoteOnlyWithoutViewport = false
    private var invalidated = false
    var scrollToRestore: Int? = null
        private set

    fun belongsTo(owner: String?, key: String?): Boolean =
        owner != null && owner == previousOwner && key == previousKey

    fun bind(owner: String?, key: String?) {
        val sameBinding = saved.get<String>(ownerKey) == owner && saved.get<String>(contextKey) == key
        if (!sameBinding) invalidate()
        saved[ownerKey] = owner
        saved[contextKey] = key.orEmpty()
    }

    /**
     * Call after the authoritative load, before form hydration or any restore offer.
     * Resolving does not decide fields: the surface calls decide only after actually applying them.
     * In particular, an uncertain blank edit may still need a successful GET to recover its body.
     */
    @Suppress("CyclomaticComplexMethod") // Ordered publication, attempt, certified resume and quote-only exceptions.
    fun resolve(
        entry: DraftEntry?, owner: String?, key: String?, allowResume: Boolean = true,
    ): EditorSessionRestore {
        val attempt = entry?.attempt
        val result = when {
            entry == null || owner == null || key == null -> EditorSessionRestore.None
            // A foreign attempt token is untrusted: neither resume nor offer its content.
            !ownsAttempt(entry, owner, key) || attempt?.status == AttemptStatus.PUBLISHED -> EditorSessionRestore.None
            attempt != null -> EditorSessionRestore.Uncertain
            allowResume && matches(entry, owner, key) -> EditorSessionRestore.Resume
            // ED-121: only an interrupted session may resume a whole, intact quote-only batch
            // without a matching pre-delivery Bundle. A fresh opening leaves the field empty.
            allowResume && !invalidated && belongsTo(owner, key) &&
                entry.draft.bodyProvenance?.isQuoteOnly(entry.draft.body) == true -> EditorSessionRestore.Resume
            entry.draft.bodyProvenance?.isQuoteOnly(entry.draft.body) == true -> EditorSessionRestore.None
            else -> EditorSessionRestore.Offer
        }
        // ED-121: a pre-delivery Bundle has no selection or viewport for the delivered body.
        if (result == EditorSessionRestore.Resume) {
            quoteOnlyWithoutViewport = !matches(checkNotNull(entry), owner, key)
        }
        // Reconciliation may reread a newer revision before the form arrives (D3).
        // Only invalidate() can revoke a resume already applied by this instance.
        resumed = resumed || (allowResume && entry?.let { matches(it, owner, key) } == true)
        return result
    }

    fun matches(entry: DraftEntry, owner: String?, key: String?): Boolean =
        !invalidated && ownsAttempt(entry, owner, key) && contentMatches(entry, owner, key)

    private fun ownsAttempt(entry: DraftEntry, owner: String?, key: String?): Boolean {
        val token = entry.attempt?.token
        return token == null || (token.owner == owner && token.key == key)
    }

    private fun contentMatches(entry: DraftEntry, owner: String?, key: String?): Boolean =
        entry.attempt?.status != AttemptStatus.PUBLISHED && belongsTo(owner, key) &&
            previousVersion == reference(entry.draft, entry.version, previousSubcategory)

    /** Applied restores and explicit edits, including clearing, must decide their fields synchronously. */
    fun decide(field: EditorSessionField) { decided += field }

    /** Use for both GET and initialRecipient: a late default cannot replace a decided blank. */
    fun canHydrate(field: EditorSessionField, liveIsBlank: Boolean): Boolean = liveIsBlank && field !in decided

    /** D3: call on the first successful form load with its actual choices (including null if offered). */
    fun subcategoryForForm(available: Set<Int?>, current: Int?): Int? {
        if (EditorSessionField.Subcategory in decided) return current
        decide(EditorSessionField.Subcategory)
        val choice = previousSubcategory
        return if (resumed && choice != null && choice.id in available) choice.id else current
    }

    /** #1574: neither a failed GET nor an undecided offer can certify an unknown/placeholder body. */
    fun canCheckpoint(bodyKnown: Boolean, hasUndecidedOffer: Boolean = false): Boolean =
        bodyKnown && !hasUndecidedOffer

    fun selection(length: Int): TextRange {
        if (quoteOnlyWithoutViewport) {
            scrollToRestore = null
            return TextRange(length)
        }
        scrollToRestore = previousScroll.coerceAtLeast(0)
        return TextRange(previousStart.coerceIn(0, length), previousEnd.coerceIn(0, length))
    }

    fun scrollRestored() { scrollToRestore = null }

    fun capture(checkpoint: EditorSessionCheckpoint, bodyKnown: Boolean): Boolean {
        if (!canCheckpoint(bodyKnown)) {
            invalidate()
            return false
        }
        saved[startKey] = checkpoint.selection.start
        saved[endKey] = checkpoint.selection.end
        saved[scrollKey] = checkpoint.scroll.coerceAtLeast(0)
        // D3: before the first successful GET, the live default cannot replace the pending choice.
        val subcategory = if (resumed && EditorSessionField.Subcategory !in decided) {
            previousSubcategory
        } else checkpoint.subcategory
        if (subcategory == null) saved.remove<Int>(subcategoryKey)
        else saved[subcategoryKey] = subcategory.id
        saved[versionKey] = reference(checkpoint.draft, checkpoint.version, subcategory)
        return true
    }

    fun completed(draft: Draft, version: Long, result: StoreWriteResult?) {
        val sameCheckpoint = saved.get<String>(versionKey) == reference(draft, version, savedSubcategory())
        // A Written result for another revision cannot certify this checkpoint either.
        if (result != StoreWriteResult.Written(version) && sameCheckpoint) invalidate()
    }

    /**
     * Revocation is sticky for this instance, including the first bind with a different owner/key.
     * Later captures may certify a new instance's resume, but cannot revive matches in this one.
     */
    fun invalidate() {
        saved.remove<String>(versionKey)
        saved.remove<Int>(subcategoryKey)
        resumed = false
        invalidated = true
    }

    private fun savedSubcategory(): EditorSessionSubcategory? =
        if (saved.contains(subcategoryKey)) EditorSessionSubcategory(saved.get<Int>(subcategoryKey)) else null

    private fun reference(draft: Draft, version: Long, subcategory: EditorSessionSubcategory?): String =
        // Preserve the original post reference when no surface-specific metadata is supplied.
        "$version:${draft.restoreOfferFingerprint()}" + (subcategory?.let { ":subcat:${it.id}" } ?: "")
}
