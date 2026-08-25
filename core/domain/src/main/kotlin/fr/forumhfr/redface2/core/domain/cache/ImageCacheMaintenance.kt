package fr.forumhfr.redface2.core.domain.cache

/**
 * Maintenance handle for the **image cache** (Coil memory + disk caches). Exposes the
 * narrow "wipe every downloaded image" surface used by Paramètres → Maintenance →
 * « Vider le cache des images » (#314) and by the auth-transition invalidator in `:core:data`
 * (#1096).
 *
 * Mirror of [TopicCacheMaintenance]: a dedicated single-capability interface keeps the
 * SettingsViewModel seam trivial (it injects exactly the maintenance it needs) and keeps
 * the image-loading read path (Coil's `AsyncImage` call sites) out of the contract.
 *
 * Implementations MUST scope I/O on a background dispatcher and MUST leave every other
 * subsystem intact:
 * - **NOT** touched: HFR cookies, auth state, DataStore preferences, Room caches
 *   (`topic_pages`, `posts`, `flag_topics`, category caches).
 * - **Touched only**: the Coil memory cache and disk cache (avatars, smileys, `[img]`
 *   pictures). Cleared images are simply re-downloaded on their next display.
 */
interface ImageCacheMaintenance {

    /**
     * Wipes the Coil memory + disk image caches. Suspending; expected to run on
     * `Dispatchers.IO` (the implementation owns the dispatcher switch — the disk clear
     * is file I/O). Throws if the underlying cache clear fails. Interactive callers must surface
     * the error so the user can retry; the auth-transition caller logs a payload-free warning and
     * continues the other independent privacy purges.
     */
    suspend fun clearImageCache()
}
