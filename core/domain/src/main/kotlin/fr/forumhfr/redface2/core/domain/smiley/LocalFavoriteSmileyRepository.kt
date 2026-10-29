package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.LocalFavoriteSmileySnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Device-local favorites, scoped to an explicitly captured HFR pseudo (lowercase only, like super
 * favorites). Blank owners are rejected; logout never clears or transfers data to another account.
 * Nonblank tokens are exact identities, including spaces and variant suffixes. No HFR or MPStorage writes.
 * Blank tokens (including a non-null move anchor) are rejected without writing storage.
 */
interface LocalFavoriteSmileyRepository {
    /** Invalid owners and unreadable documents emit an empty list without repairing storage. */
    fun observeFavorites(owner: String): Flow<List<LocalFavoriteSmiley>>

    /** Appends a new favorite; an existing token keeps its metadata, timestamp and position. */
    suspend fun add(owner: String, token: String, imageUrl: String? = null): LocalFavoriteSmileyMutationResult

    /**
     * Idempotent removal; only an active token creates a tombstone.
     * Prunes the oldest tombstones only when needed to fit the document byte limit.
     */
    suspend fun remove(owner: String, token: String): LocalFavoriteSmileyMutationResult

    /** Moves before an active token, or to the end for null. Missing tokens and self-moves do nothing. */
    suspend fun move(owner: String, token: String, beforeToken: String?): LocalFavoriteSmileyMutationResult

    /** Includes tombstones and dates, never the owner. Read failures are explicit. */
    suspend fun exportSnapshot(owner: String): LocalFavoriteSmileyExportResult

    /** Explicitly erases this owner's valid document, including tombstones. Unreadable data is preserved. */
    suspend fun clear(owner: String): LocalFavoriteSmileyMutationResult

    companion object {
        const val MAX_FAVORITES = 256
        const val MAX_DOCUMENT_BYTES = 256 * 1024
    }
}

sealed interface LocalFavoriteSmileyMutationResult {
    data object Added : LocalFavoriteSmileyMutationResult
    data object AlreadyPresent : LocalFavoriteSmileyMutationResult
    data object LimitReached : LocalFavoriteSmileyMutationResult
    data object Updated : LocalFavoriteSmileyMutationResult
    data object Unchanged : LocalFavoriteSmileyMutationResult
    data class Failure(val reason: LocalFavoriteSmileyFailure) : LocalFavoriteSmileyMutationResult
}

sealed interface LocalFavoriteSmileyExportResult {
    data class Success(val snapshot: LocalFavoriteSmileySnapshot) : LocalFavoriteSmileyExportResult
    data class Failure(val reason: LocalFavoriteSmileyFailure) : LocalFavoriteSmileyExportResult
}

enum class LocalFavoriteSmileyFailure {
    INVALID_OWNER,
    INVALID_TOKEN,
    UNREADABLE_DOCUMENT,
    DOCUMENT_TOO_LARGE,
    STORAGE_FAILURE,
}
