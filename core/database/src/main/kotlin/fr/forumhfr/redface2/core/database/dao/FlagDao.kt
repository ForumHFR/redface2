package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant

/**
 * Persistence boundary for the user's drapeaux. The schema keys rows by
 * `(userId, type, cat, topicId)` — `userId` is the **lowercased pseudo** of
 * the authenticated account.
 *
 * **Convention** : every caller MUST `.lowercase()` the pseudo before invoking
 * any of these methods. The DAO does not normalise on its own — Room equality
 * is byte-exact and `getFlags("Alice")` returns nothing if the row was upserted
 * with `userId = "alice"`. The normalisation lives at the repository / mapper
 * boundary so the DAO contract stays a thin SQL wrapper.
 *
 * `CacheInvalidator` already lowercases pseudos before calling [deleteAllForUser]
 * (cf. `core.data.cache.CacheInvalidator`). When the repository write path lands
 * (PR #112 → flag persistence), the same lowercase pass is mandatory at insert.
 */
@Dao
interface FlagDao {

    @Query(
        "SELECT * FROM flag_topics WHERE userId = :userId AND type = :type " +
            "ORDER BY lastReplyAt DESC",
    )
    suspend fun getFlags(userId: String, type: FlagType): List<FlagTopicEntity>

    @Query("SELECT * FROM flag_category_coverage WHERE userId = :userId AND type = :type")
    suspend fun getCoverage(userId: String, type: FlagType): List<FlagCategoryCoverageEntity>

    @Transaction
    suspend fun getSnapshot(userId: String, type: FlagType): FlagCacheSnapshot =
        FlagCacheSnapshot(getFlags(userId, type), getCoverage(userId, type))

    /**
     * Legacy row timestamp query. Freshness decisions use the complete coverage snapshot,
     * including empty partitions, rather than MAX(fetchedAt).
     */
    @Query(
        "SELECT MAX(fetchedAt) FROM flag_topics WHERE userId = :userId AND type = :type",
    )
    suspend fun getLastFetchedAt(userId: String, type: FlagType): Instant?

    @Upsert
    suspend fun upsertAll(rows: List<FlagTopicEntity>)

    @Query("DELETE FROM flag_topics WHERE userId = :userId AND type = :type")
    suspend fun deleteRowsForType(userId: String, type: FlagType)

    @Query("DELETE FROM flag_category_coverage WHERE userId = :userId AND type = :type")
    suspend fun deleteCoverageForType(userId: String, type: FlagType)

    @Transaction
    suspend fun deleteForType(userId: String, type: FlagType) {
        deleteRowsForType(userId, type)
        deleteCoverageForType(userId, type)
    }

    @Upsert
    suspend fun upsertCoverage(rows: List<FlagCategoryCoverageEntity>)

    /**
     * Removes a single cached drapeau row after a successful `delflag.php` (#99). The
     * logical key is `(userId, type, cat, topicId)` — the same `cat + topicId` pair the
     * UI uses as a Compose list key (unique within a category, cf. AGENTS.md) scoped to
     * the flag's bucket [type]. `subcat` is intentionally **not** part of the predicate :
     * REST flag listings do not always carry it, and a row is uniquely identified without
     * it. No-op (0 rows) when the row is already gone — harmless, the network deletion is
     * the source of truth.
     */
    @Query(
        "DELETE FROM flag_topics WHERE userId = :userId AND type = :type " +
            "AND cat = :cat AND topicId = :topicId",
    )
    suspend fun deleteFlag(userId: String, type: FlagType, cat: Int, topicId: Int)

    @Query(
        "UPDATE flag_topics SET isFavorite = 1 WHERE userId = :userId AND type = :type " +
            "AND cat = :cat AND topicId = :topicId",
    )
    suspend fun markFavorite(userId: String, type: FlagType, cat: Int, topicId: Int)

    @Query("DELETE FROM flag_topics WHERE userId = :userId")
    suspend fun deleteRowsForUser(userId: String)

    @Query("DELETE FROM flag_category_coverage WHERE userId = :userId")
    suspend fun deleteCoverageForUser(userId: String)

    @Transaction
    suspend fun deleteAllForUser(userId: String) {
        deleteRowsForUser(userId)
        deleteCoverageForUser(userId)
    }

    /** Used by [fr.forumhfr.redface2.core.data.cache.CacheInvalidator] on logout. */
    @Query("DELETE FROM flag_topics")
    suspend fun deleteAllRows()

    @Query("DELETE FROM flag_category_coverage")
    suspend fun deleteAllCoverage()

    @Transaction
    suspend fun deleteAll() {
        deleteAllRows()
        deleteAllCoverage()
    }

    /** One write transaction per refresh; unchanged partitions keep their original row dates. */
    @Transaction
    suspend fun replaceSnapshot(userId: String, type: FlagType, snapshot: FlagCacheSnapshot) {
        deleteForType(userId, type)
        upsertAll(snapshot.rows)
        upsertCoverage(snapshot.coverage)
    }

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

data class FlagCacheSnapshot(
    val rows: List<FlagTopicEntity>,
    val coverage: List<FlagCategoryCoverageEntity>,
)
