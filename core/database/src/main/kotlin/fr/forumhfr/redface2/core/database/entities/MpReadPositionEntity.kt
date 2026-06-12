package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity

/**
 * Last page the user actually displayed in one private-message conversation (#430, ADR-013
 * stage 1). Written on every successfully landed page; read once when (re)opening the
 * conversation so a process death — or a later re-opening from the inbox — resumes past the
 * route's frozen opening page.
 *
 * Per-account isolation follows [FlagTopicEntity]: [userId] (HFR pseudo, lowercase) is part of
 * the primary key and the table is wiped by user on logout/account switch (cf. CacheInvalidator)
 * — MP metadata is private, no row may survive the session that produced it. Only the page
 * NUMBER is stored: no subject, no correspondent, no message content (same privacy rule as the
 * deliberately opaque `PrivateMessageThreadRoute`).
 */
@Entity(
    tableName = "mp_read_positions",
    primaryKeys = ["userId", "threadId"],
)
data class MpReadPositionEntity(
    /** Lowercased HFR pseudo of the account that owns this row. */
    val userId: String,
    /** HFR `post` id of the conversation (unique within `cat=prive`). */
    val threadId: Int,
    /** Last conversation page the user had on screen. */
    val page: Int,
)
