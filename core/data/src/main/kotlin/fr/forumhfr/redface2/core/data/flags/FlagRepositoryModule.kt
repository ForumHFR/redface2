package fr.forumhfr.redface2.core.data.flags

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.parser.write.FlagAddResponseParser
import fr.forumhfr.redface2.core.parser.write.FlagDeleteResponseParser
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Internal qualifier for the lenient [Json] used by the REST flag mapper. Mirrors the
 * `ForumJson` qualifier in `:core:data`'s `forum` package — kept distinct so a future
 * configuration tweak (e.g. a custom serializer for a flag-specific field) can land
 * without touching the forum binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class FlagsJson

@Module
@InstallIn(SingletonComponent::class)
abstract class FlagRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFlagRepository(impl: DefaultFlagRepository): FlagRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal object FlagsJsonModule {

    /**
     * Lenient JSON: REST flag responses ship a few keys we do not model
     * (`linked_type`, `type` discriminators, `tns3` avatar filenames). Ignoring unknown
     * keys keeps the contract additive — adding a new field upstream does not break
     * parsing.
     */
    @Provides
    @Singleton
    @FlagsJson
    fun provideFlagsJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Stateless parser for the `addflag.php` response (#986). Provided rather than
     * `@Inject`-constructed to mirror the `ReplySubmitResponseParser` binding in
     * `ReplyRepositoryModule` — `:core:parser` classes stay free of DI annotations.
     */
    @Provides
    @Singleton
    fun provideFlagAddResponseParser(): FlagAddResponseParser = FlagAddResponseParser()

    /**
     * Stateless parser for the `delflag.php` response (#99). Provided rather than
     * `@Inject`-constructed to mirror the `ReplySubmitResponseParser` binding in
     * `ReplyRepositoryModule` — `:core:parser` classes stay free of DI annotations.
     */
    @Provides
    @Singleton
    fun provideFlagDeleteResponseParser(): FlagDeleteResponseParser = FlagDeleteResponseParser()
}
