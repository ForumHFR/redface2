package fr.forumhfr.redface2.core.data.forum

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.model.ForumIndex
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.forum.ForumCategoriesParser
import fr.forumhfr.redface2.core.parser.forum.TopicListParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Phase 1C-A network-only implementation of [ForumRepository]. Both calls always hit
 * HFR — no in-memory or Room cache yet. The forum index is small (~20 categories) and
 * topic lists are paginated, so a fresh fetch on every call is acceptable for this
 * slice. Cache-aside arrives in a later phase once the navigation policy is settled.
 *
 * Failures (network, session expiry, parser error) are caught into `Result.failure` so
 * the caller can map them to a typed UiState. We intentionally keep the call sites
 * symmetric with [DefaultFlagRepository] / [DefaultMessagesRepository] for consistency.
 */
@Singleton
class DefaultForumRepository @Inject constructor(
    private val client: HfrClient,
    private val categoriesParser: ForumCategoriesParser,
    private val topicListParser: TopicListParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ForumRepository {

    override suspend fun getForumIndex(): Result<ForumIndex> = withContext(ioDispatcher) {
        runCatching { categoriesParser.parse(client.getForumHomePage(useAuth = true)) }
    }

    override suspend fun getTopicList(cat: Int, subcat: Int, page: Int): Result<TopicListPage> =
        withContext(ioDispatcher) {
            runCatching { topicListParser.parse(client.getTopicListPage(cat, subcat, page, useAuth = true)) }
        }
}
