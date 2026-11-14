package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * ED-121: immutable offered version, independent of active content and publication tombstones.
 * No foreign key to C: publishing/erasing C must retain D. Private offers are purged on logout.
 * Each version keeps its own last modification date; never log its content or identity.
 */
@Entity(
    tableName = "editor_draft_offers",
    primaryKeys = ["ownerId", "contextKey", "offerId"],
    indices = [Index(value = ["updatedAt"])],
)
data class EditorDraftOfferEntity(
    val ownerId: String,
    val contextKey: String,
    val offerId: String,
    val sourceVersion: Long,
    val body: String,
    val subject: String?,
    val recipients: String?,
    val updatedAt: Long,
    val isPrivate: Boolean,
)
