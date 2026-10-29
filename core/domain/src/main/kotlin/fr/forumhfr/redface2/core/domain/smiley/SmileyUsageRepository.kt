package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import kotlinx.coroutines.flow.Flow

/** Local frequency ranking scoped to an explicitly captured owner, lowercase only, without trim. */
interface SmileyUsageRepository {
    /** Count descending, last use descending, token ascending. Invalid/unreadable owners emit empty. */
    fun observeTop(owner: String, limit: Int = TOP_LIMIT): Flow<List<SmileyUsageEntry>>

    /** Adds positive occurrences atomically. Blank owners/tokens and nonpositive counts are refused. */
    suspend fun record(owner: String, usages: Map<String, Int>): SmileyUsageMutationResult

    /** Explicit erasure, including across logout. An unreadable document is always preserved. */
    suspend fun clear(owner: String): SmileyUsageMutationResult

    companion object {
        const val MAX_ENTRIES = 512
        const val TOP_LIMIT = 24
    }
}

sealed interface SmileyUsageMutationResult {
    data object Updated : SmileyUsageMutationResult
    data object Unchanged : SmileyUsageMutationResult
    data class Failure(val reason: SmileyUsageFailure) : SmileyUsageMutationResult
}

enum class SmileyUsageFailure {
    INVALID_OWNER,
    INVALID_USAGES,
    UNREADABLE_DOCUMENT,
    STORAGE_FAILURE,
}
