package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult

/**
 * Phase 2F-B (#11 partial) — repository surface for the editor smiley picker.
 *
 * Live search uses HFR's `message-smi-mp-aj.php`; public detail keywords use `wikismilies.php`.
 * The Standard tab does not need a repository call because it ships as a
 * static constant (`BUILTIN_HFR_SMILEYS`) sourced from the form fixture.
 *
 * Out of scope for #11 partial : favourites, recents, MPStorage sync, perso upload,
 * offline catalogue. These will land in dedicated repositories when their issues open.
 */
interface SmileyRepository {

    /**
     * Searches HFR's perso smiley wiki for [query]. HFR's web composer triggers this at
     * `query.length > 2` with a 300 ms debounce — callers should respect the same gate
     * to avoid hammering the endpoint.
     *
     * [userId] is the logged-in user's HFR id, parsed from the form HTML (cf.
     * `ReplyForm.userId` / `TopicForm.userId`). When unknown, callers pass `0` ; HFR
     * still returns matches but favourites are not paged first.
     *
     * Returns the matching smileys deduplicated by `(token, imageUrl)`. Implementations
     * raise on network / parse errors — the ViewModel translates to a sober UI state.
     */
    suspend fun searchWiki(userId: Int, query: String): List<EditorSmiley>

    /** Synchronous process-cache read, so reopening a detail never flashes Loading on a cache hit. */
    fun cachedWikiKeywords(token: String): SmileyWikiKeywordsResult.Loaded?

    /** Anonymous, account-independent metadata. Failures are not cached; cancellation propagates. */
    suspend fun getWikiKeywords(token: String): SmileyWikiKeywordsResult
}
