package fr.forumhfr.redface2.core.domain.editor

import java.security.MessageDigest

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
 * wiped on logout/account switch (CacheInvalidator); all drafts older than the retention TTL are
 * pruned on app start. No draft content is ever logged or surfaced in diagnostics.
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
     * Upserts [draft] under [key] for [owner], stamping `updatedAt` from the clock. No-op when
     * [owner] is null (anonymous session) OR when [owner] is no longer the active account: the
     * draft belongs to a session whose account has switched or logged out, so persisting it would
     * either leak it to the new account or revive a row the logout purge already swept.
     * [Draft.body]/[Draft.subject]/[Draft.recipients] are the raw editor fields; [Draft.isPrivate]
     * marks MP drafts for the logout purge.
     */
    suspend fun save(owner: String?, key: String, draft: Draft)

    /**
     * Deletes [owner]'s draft under [key] (called on a successful POST). No-op when [owner] is null.
     * Best-effort: it is awaited on the post-success path, so it MUST NOT throw on a local DB
     * failure — a surviving row only costs a spurious restore prompt and must never abort a
     * successful post. (Genuine coroutine cancellation still propagates.)
     */
    suspend fun delete(owner: String?, key: String)

    data class Draft(
        val body: String,
        val subject: String? = null,
        val recipients: String? = null,
        val isPrivate: Boolean = false,
        /** Epoch millis of last write; set by the store, ignored on save input. */
        val updatedAt: Long = 0L,
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
