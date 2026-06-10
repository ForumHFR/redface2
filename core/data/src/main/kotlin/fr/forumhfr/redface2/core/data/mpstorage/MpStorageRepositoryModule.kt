package fr.forumhfr.redface2.core.data.mpstorage

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageDiscoveryParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import javax.inject.Singleton

/**
 * MPStorage (#6, ADR-014) — Hilt wiring. Same pattern as [fr.forumhfr.redface2.core.data
 * .search.SearchRepositoryModule] : `@Binds` for the interface, `@Provides` for the pure
 * parsers (no `@Inject` constructors). `PrivateMessageThreadParser` / `ReplyFormParser`
 * are already provided by `PlatformBindingsModule` / the write modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MpStorageRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMpStorageRepository(impl: DefaultMpStorageRepository): MpStorageRepository

    companion object {
        @Provides
        @Singleton
        fun provideMpStorageParser(): MpStorageParser = MpStorageParser()

        @Provides
        @Singleton
        fun provideMpStorageDiscoveryParser(): MpStorageDiscoveryParser = MpStorageDiscoveryParser()
    }
}
