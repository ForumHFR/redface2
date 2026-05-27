package fr.forumhfr.redface2.core.domain.cache

/**
 * Alpha-only maintenance handle for the **topic cache** (Room tables `topic_pages` +
 * `posts`). Exposes the narrow "wipe everything topic-related" surface used by
 * Paramètres alpha → « Vider le cache des topics ».
 *
 * Deliberately not part of `TopicRepository`: that interface owns the read path
 * (`observeTopicPage`, `refreshTopicPage`, `prefetch`) and adding a maintenance entry
 * point there would muddy the contract. Keeping it on its own interface also makes the
 * test seam trivial (the SettingsViewModel injects this single capability, not a full
 * topic repo).
 *
 * Implementations MUST scope I/O on a background dispatcher and MUST leave every other
 * subsystem intact:
 * - **NOT** touched: HFR cookies, auth state, DataStore preferences (proxy etc.),
 *   flag tables (`flag_topics`), category caches.
 * - **Touched only**: `topic_pages` + `posts`.
 *
 * Used after a parser/AST evolution to force a re-fetch + re-parse on the next topic
 * read, since `TopicRepository.observeTopicPage` is cache-first and will otherwise
 * serve an outdated `PostContent` AST.
 */
interface TopicCacheMaintenance {

    /**
     * Wipes the cached topic pages + posts. Suspending; expected to run on
     * `Dispatchers.IO` (the implementation owns the dispatcher switch). Throws if the
     * underlying Room transaction fails — callers must surface the error rather than
     * swallowing it so a user can retry instead of staring at a silently-no-op button.
     */
    suspend fun clearTopicCache()
}
