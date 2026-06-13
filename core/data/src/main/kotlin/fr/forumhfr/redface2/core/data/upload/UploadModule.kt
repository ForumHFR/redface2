package fr.forumhfr.redface2.core.data.upload

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Lenient [Json] for the upload providers (#459) — same profile as `@ForumJson`. Distinct qualifier
 * so the upload parsing stays isolated from the Forum REST mapper's instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class UploadJson

/**
 * The user's imgur Client-ID (#459, option B). Injected as a `javax.inject.Provider<String>` into
 * [ImgurProvider] so each upload re-reads the current preference value. Empty when not configured.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ImgurClientId

@Module
@InstallIn(SingletonComponent::class)
internal interface UploadProviderBindingsModule {

    @Binds
    @Singleton
    fun bindUploadRepository(impl: DefaultUploadRepository): UploadRepository

    @Binds
    @IntoMap
    @UploadProviderKey(UploadProviderId.DIBERIE)
    fun bindDiberieProvider(impl: DiberieProvider): UploadProvider

    @Binds
    @IntoMap
    @UploadProviderKey(UploadProviderId.IMGUR)
    fun bindImgurProvider(impl: ImgurProvider): UploadProvider
}

@Module
@InstallIn(SingletonComponent::class)
internal object UploadModule {

    @Provides
    @Singleton
    @UploadJson
    fun provideUploadJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // @Suppress: a DI binding that intentionally returns the production base URL constant. Tests
    // inject a MockWebServer URL through the provider constructor instead of this binding.
    @Provides
    @Named(DiberieProvider.DIBERIE_BASE_URL)
    @Suppress("FunctionOnlyReturningConstant")
    fun provideDiberieBaseUrl(): String = DiberieProvider.DEFAULT_BASE_URL

    @Provides
    @Named(ImgurProvider.IMGUR_BASE_URL)
    @Suppress("FunctionOnlyReturningConstant")
    fun provideImgurBaseUrl(): String = ImgurProvider.DEFAULT_BASE_URL

    /**
     * Reads the user's imgur Client-ID from the preference (option B). Synchronous — invoked via a
     * `javax.inject.Provider` on each upload, off the main thread inside the provider's
     * `withContext(ioDispatcher)`. Empty string when the user has not configured imgur (the
     * selector then hides IMGUR). Same synchronous-bridge pattern as
     * `UserPreferencesRepository.readProxyConfigForNetworkBootstrap`.
     */
    @Provides
    @ImgurClientId
    fun provideImgurClientId(
        userPreferencesRepository: UserPreferencesRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): String = runBlocking(ioDispatcher) {
        userPreferencesRepository.observeImgurClientId().first()
    }
}
