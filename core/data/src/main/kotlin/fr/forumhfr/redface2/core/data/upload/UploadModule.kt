package fr.forumhfr.redface2.core.data.upload

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Lenient [Json] for the upload providers (#459) — same profile as `@ForumJson`. Distinct qualifier
 * so the upload parsing stays isolated from the Forum REST mapper's instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class UploadJson

@Module
@InstallIn(SingletonComponent::class)
internal interface UploadProviderBindingsModule {

    @Binds
    @Singleton
    fun bindUploadRepository(impl: DefaultUploadRepository): UploadRepository

    // PR2 (#459) — the editor reads the picked Uri's bytes through this seam ; the Android impl
    // lives here (it needs ContentResolver) so the editor ViewModel stays platform-free.
    @Binds
    @Singleton
    fun bindImageUploadReader(impl: AndroidImageUploadReader): ImageUploadReader

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

    // #474 — the imgur Client-ID is no longer bridged through a runBlocking @Provides. ImgurProvider
    // now reads `UserPreferencesRepository.observeImgurClientId().first()` directly inside its
    // suspending `withContext(ioDispatcher)`, so the preference is collected without blocking a
    // thread and the value is still re-read on every upload/delete.
}
