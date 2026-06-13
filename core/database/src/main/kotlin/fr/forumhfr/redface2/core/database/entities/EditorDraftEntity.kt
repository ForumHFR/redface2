package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached in-progress editor content (#405). One row per (account, edit context); [draftKey] is
 * `"<ownerId>|<contextKey>"` so it stays globally unique while embedding the owning account.
 * The `|` separator is safe: HFR pseudos use the charset `[a-zA-Z0-9 _-]`, so a pseudo can never
 * contain `|`.
 *
 * Privacy: [ownerId] (HFR pseudo, lowercase) lets the logout/account-switch purge wipe a single
 * account's drafts exactly (CacheInvalidator), without a LIKE-prefix scan. [isPrivate] rows are
 * MP drafts — wiped on the same transition (an MP draft reveals a recipient + message). Public
 * post drafts survive logout but are bounded by the 30-day retention purge run on app start.
 * Stored content is never logged or sent to diagnostics (#405).
 */
@Entity(tableName = "editor_drafts")
data class EditorDraftEntity(
    /** Row key: `"<ownerId>|<contextKey>"` ([EditorDraftKey] context prefixed with [ownerId]). */
    @PrimaryKey val draftKey: String,
    /** Lowercased HFR pseudo of the account that owns this draft (purge by user). */
    val ownerId: String,
    /** Raw editor body (BBCode) — never logged. */
    val body: String,
    /** Raw subject for the editors that have one (new topic, edit first post, MP compose). */
    val subject: String?,
    /** Raw recipient list — only the new-MP composer fills it; private, purged on logout. */
    val recipients: String?,
    /** Epoch millis of last write, stamped from the clock. */
    val updatedAt: Long,
    /** True for MP drafts (`mpreply`/`mpnew`): wiped on logout/account switch. */
    val isPrivate: Boolean,
)
