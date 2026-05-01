package fr.forumhfr.redface2.core.domain.forum

import fr.forumhfr.redface2.core.model.ForumIndex
import fr.forumhfr.redface2.core.model.TopicListPage

/**
 * Read access to the HFR forum index (root catégories) and to per-(sub)category topic
 * lists. Phase 1C-A is network-only — no Room cache yet (cf. roadmap `1D.2`).
 *
 * Both methods return `Result` so the caller (ViewModel) can map a failure to a typed
 * UI error (`SessionExpiredException`, `IOException`, …) without try/catch boilerplate.
 */
interface ForumRepository {
    /** Fetches and parses the forum root page. */
    suspend fun getForumIndex(): Result<ForumIndex>

    /**
     * Fetches and parses one page of a (sub)category's topic list.
     *
     * @param cat numeric HFR category ID (must be `> 0`).
     * @param subcat `0` = list all subcategories of `cat`; `> 0` = scope to subcategory.
     * @param page 1-based page number.
     */
    suspend fun getTopicList(cat: Int, subcat: Int = 0, page: Int = 1): Result<TopicListPage>
}
