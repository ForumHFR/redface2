package fr.forumhfr.redface2.core.domain.editor

/**
 * Per-account local cache of in-progress editor content (#405). Implementations resolve the
 * active account themselves and MUST be a no-op (read returns null / save & delete do nothing)
 * when no session is active — drafts are private per-account data.
 *
 * [load]/[save]/[delete] take a stable, content-free context [key] built by [EditorDraftKey]; it
 * never embeds message bodies, subjects or recipients. MP drafts ([Draft.isPrivate] == true) are
 * wiped on logout/account switch (CacheInvalidator); all drafts older than the retention TTL are
 * pruned on app start. No draft content is ever logged or surfaced in diagnostics.
 */
interface EditorDraftStore {

    /** One-shot read of the draft stored under [key] for the active account, or null. */
    suspend fun load(key: String): Draft?

    /**
     * Upserts [draft] under [key] for the active account, stamping `updatedAt` from the clock.
     * No-op without an active session. [Draft.body]/[Draft.subject]/[Draft.recipients] are the
     * raw editor fields; [Draft.isPrivate] marks MP drafts for the logout purge.
     */
    suspend fun save(key: String, draft: Draft)

    /** Deletes the draft under [key] for the active account (called on a successful POST). */
    suspend fun delete(key: String)

    data class Draft(
        val body: String,
        val subject: String? = null,
        val recipients: String? = null,
        val isPrivate: Boolean = false,
        /** Epoch millis of last write; set by the store, ignored on save input. */
        val updatedAt: Long = 0L,
    )
}
