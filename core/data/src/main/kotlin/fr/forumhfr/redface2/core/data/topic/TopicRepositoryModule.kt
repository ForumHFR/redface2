package fr.forumhfr.redface2.core.data.topic

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TopicRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTopicRepository(impl: TopicRepositoryImpl): TopicRepository

    // Chantier C (#546) — intra-topic search (transsearch.php).
    @Binds
    @Singleton
    abstract fun bindTopicSearchRepository(impl: TopicSearchRepositoryImpl): TopicSearchRepository
}
