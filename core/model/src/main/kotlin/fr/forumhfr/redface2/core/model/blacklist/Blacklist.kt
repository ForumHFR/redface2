package fr.forumhfr.redface2.core.model.blacklist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One blacklisted (hidden) HFR user.
 *
 * - [canonical] is the normalised match key (see `canonicalizePseudo` in `:core:domain`) — what we
 *   compare a post author against. It is what makes "Foo", "foo" and " foo " collapse to one entry.
 * - [display] preserves the spelling first seen when the user was blocked, for the management screen.
 *   When two spellings normalise to the same [canonical], the first one wins (no duplicate entry).
 * - [addedAt] is an epoch-millis timestamp, kept so a future MPStorage sync can resolve conflicts and
 *   so the management list can be ordered by recency if desired.
 *
 * Stored as a versioned JSON document ([BlacklistDocument]) rather than a `Set<String>` so the schema
 * can grow additively (block reason, per-category/topic scope, sync origin) without breaking the
 * production key. Every field carries an explicit [SerialName] for forward-compatible persistence.
 */
@Serializable
data class BlacklistEntry(
    @SerialName("canonical") val canonical: String,
    @SerialName("display") val display: String,
    @SerialName("addedAt") val addedAt: Long,
)

/**
 * Versioned container persisted for the local blacklist. [version] lets a later reader migrate an
 * older shape; [entries] is kept in insertion order so the management screen is stable.
 */
@Serializable
data class BlacklistDocument(
    @SerialName("version") val version: Int = CURRENT_VERSION,
    @SerialName("entries") val entries: List<BlacklistEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
