package fr.forumhfr.redface2.core.domain.fixtures

import fr.forumhfr.redface2.core.model.Topic

interface TopicFixtureRepository {
    suspend fun loadTopicPage(page: Int): Topic
}
