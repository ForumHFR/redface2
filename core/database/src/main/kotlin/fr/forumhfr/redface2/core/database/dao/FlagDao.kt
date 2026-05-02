package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant

@Dao
interface FlagDao {

    @Query(
        "SELECT * FROM flag_topics WHERE userId = :userId AND type = :type " +
            "ORDER BY lastReplyAt DESC",
    )
    suspend fun getFlags(userId: String, type: FlagType): List<FlagTopicEntity>

    /**
     * Most recent successful fetch for a (userId, type) pair, or `null` if the
     * tab has never been hydrated. Drives "is the cache fresh enough to skip
     * the network" decisions in the repository.
     */
    @Query(
        "SELECT MAX(fetchedAt) FROM flag_topics WHERE userId = :userId AND type = :type",
    )
    suspend fun getLastFetchedAt(userId: String, type: FlagType): Instant?

    @Upsert
    suspend fun upsertAll(rows: List<FlagTopicEntity>)

    @Query("DELETE FROM flag_topics WHERE userId = :userId AND type = :type")
    suspend fun deleteForType(userId: String, type: FlagType)

    @Query("DELETE FROM flag_topics WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    /** Used by [fr.forumhfr.redface2.core.data.cache.CacheInvalidator] on logout. */
    @Query("DELETE FROM flag_topics")
    suspend fun deleteAll()

    /**
     * Atomic replace of the cached drapeaux for a tab. The two-statement form
     * matches what HFR returns (a full listing per tab, not a delta) and keeps
     * the table free of stale rows that disappeared from the upstream.
     */
    @Transaction
    suspend fun replaceForType(userId: String, type: FlagType, rows: List<FlagTopicEntity>) {
        deleteForType(userId, type)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}
