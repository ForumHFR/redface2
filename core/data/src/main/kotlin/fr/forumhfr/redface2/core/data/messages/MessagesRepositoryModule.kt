package fr.forumhfr.redface2.core.data.messages

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCache
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MessagesRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMessagesRepository(impl: DefaultMessagesRepository): MessagesRepository

    @Binds
    @Singleton
    abstract fun bindPrivateMessageReadPositionStore(
        impl: RoomPrivateMessageReadPositionStore,
    ): PrivateMessageReadPositionStore

    @Binds
    @Singleton
    internal abstract fun bindPrivateMessageThreadDiskCache(
        impl: RoomPrivateMessageThreadDiskCache,
    ): PrivateMessageThreadDiskCache

    @Binds
    @Singleton
    internal abstract fun bindPrivateContentDatabaseScrubber(
        impl: RoomPrivateContentDatabaseScrubber,
    ): PrivateContentDatabaseScrubber

    @Binds
    @Singleton
    abstract fun bindPrivateMessageContentCache(
        impl: DataStorePrivateMessageContentCache,
    ): PrivateMessageContentCache

    @Binds
    @Singleton
    internal abstract fun bindPrivateMessageContentCacheMaintenance(
        impl: DataStorePrivateMessageContentCache,
    ): PrivateMessageContentCacheMaintenance

    @Binds
    @Singleton
    internal abstract fun bindPrivateMessageContentAccess(
        impl: DataStorePrivateMessageContentCache,
    ): PrivateMessageContentAccess
}
