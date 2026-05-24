package fr.forumhfr.redface2.core.data.cache

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TopicCacheMaintenanceModule {

    @Binds
    @Singleton
    abstract fun bindTopicCacheMaintenance(
        impl: DefaultTopicCacheMaintenance,
    ): TopicCacheMaintenance
}
