package fr.forumhfr.redface2.core.data.mpstorage

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageReadPositionSeeder
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import javax.inject.Singleton

/**
 * MPStorage (#6, ADR-014) — Hilt wiring. `@Binds` for the repository, the per-account location
 * cache and the DT read-position seeder; `@Provides` for the pure [MpStorageParser] (no `@Inject`
 * constructor). `PrivateMessageListParser` / `PrivateMessageThreadParser` / `ReplyFormParser` are
 * already provided by `PlatformBindingsModule` / the write modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MpStorageRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMpStorageRepository(impl: DefaultMpStorageRepository): MpStorageRepository

    @Binds
    @Singleton
    abstract fun bindMpStorageLocationStore(impl: RoomMpStorageLocationStore): MpStorageLocationStore

    @Binds
    @Singleton
    abstract fun bindMpStorageReadPositionSeeder(
        impl: DefaultMpStorageReadPositionSeeder,
    ): MpStorageReadPositionSeeder

    companion object {
        @Provides
        @Singleton
        fun provideMpStorageParser(): MpStorageParser = MpStorageParser()
    }
}
