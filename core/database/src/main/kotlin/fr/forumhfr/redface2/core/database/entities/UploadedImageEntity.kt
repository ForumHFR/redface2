package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import java.time.Instant

/**
 * One previously uploaded image, persisted per account (#459) for the « Mes images uploadées »
 * screen and deferred deletion. Rows are written after a successful upload from the new build only
 * (no backfill).
 *
 * Per-account isolation follows [FlagTopicEntity] / [MpReadPositionEntity]: [userId] (HFR pseudo,
 * lowercase) is part of the primary key and the table is wiped by user on logout / account switch
 * (cf. `CacheInvalidator`). The deletion handles are private per account — no row may survive the
 * session that produced it.
 *
 * The primary key uses [picId] as the per-provider unique handle: diberie `picID` and imgur
 * `deletehash` are both unique within their provider and double as the deletion handle, so
 * `(userId, provider, picId)` uniquely identifies an image without an extra surrogate key.
 */
@Entity(
    tableName = "uploaded_images",
    primaryKeys = ["userId", "provider", "picId"],
    indices = [Index(value = ["userId", "uploadedAt"])],
)
data class UploadedImageEntity(
    /** Lowercased HFR pseudo of the account that owns this row. */
    val userId: String,
    /** `UploadProviderId.name` — `DIBERIE` | `IMGUR`. */
    val provider: String,
    /** Per-provider stable handle: `picID` (diberie) | `deletehash` (imgur). PK + deletion handle. */
    val picId: String,
    val imageUrl: String,
    val thumbnailUrl: String?,
    /** Redundant with [picId] today, kept explicit: `null` means deletion is no longer possible. */
    val deleteHandle: String?,
    val uploadedAt: Instant,
    val expiresAt: Instant?,
)
