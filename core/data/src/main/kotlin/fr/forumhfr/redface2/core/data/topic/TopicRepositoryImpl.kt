package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val topicDao: TopicDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicRepository {

    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> = flow {
        val cached = withContext(ioDispatcher) { loadFromCache(cat, post, page) }
        if (cached != null) emit(cached)
        emit(refreshTopicPage(cat, post, page))
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic =
        withContext(ioDispatcher) {
            val html = client.getTopicPage(cat = cat, post = post, page = page, useAuth = true)
            val topic = parser.parseTopicPage(html)
            val (topicEntity, postEntities) = TopicMappers.toEntities(topic, clock.instant())
            topicDao.upsertTopicPageWithPosts(topicEntity, postEntities)
            topic
        }

    private suspend fun loadFromCache(cat: Int, post: Int, page: Int): Topic? {
        val topicEntity = topicDao.getTopicPage(cat, post, page) ?: return null
        val postEntities = topicDao.getPostsByNumreponse(cat, topicEntity.numreponses)
        return TopicMappers.toDomain(topicEntity, postEntities)
    }
}
