package fr.forumhfr.redface2.core.data.stats

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRecorder
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRepository

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkStatsModule {
    @Binds fun recorder(buffer: NetworkStatsBuffer): NetworkStatsRecorder
    @Binds fun repository(writer: NetworkStatsWriter): NetworkStatsRepository
    @Binds fun store(room: RoomNetworkStatsStore): NetworkStatsStore
}
