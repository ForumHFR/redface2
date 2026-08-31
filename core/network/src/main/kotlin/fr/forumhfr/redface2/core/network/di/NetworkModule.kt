package fr.forumhfr.redface2.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import fr.forumhfr.redface2.core.network.qualifiers.MutationClient
import fr.forumhfr.redface2.core.network.qualifiers.UploadClient
import java.time.Duration
import javax.inject.Singleton
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    @HfrBaseUrl
    fun provideHfrBaseUrl(): HttpUrl = HfrConstants.BASE_URL.toHttpUrl()

    @Provides
    @Singleton
    fun provideBaseClient(userPreferencesRepository: UserPreferencesRepository): OkHttpClient =
        OkHttpClient.Builder()
        .connectTimeout(HfrConstants.ConnectTimeout)
        .readTimeout(HfrConstants.ReadTimeout)
        .writeTimeout(HfrConstants.WriteTimeout)
        .callTimeout(HfrConstants.CallTimeout)
        .addInterceptor(UserAgentInterceptor(HfrConstants.USER_AGENT))
        .applyProxyConfig(userPreferencesRepository.readProxyConfigForNetworkBootstrap())
        .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedClient(
        baseClient: OkHttpClient,
        cookieJar: CookieJar,
    ): OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .build()

    @Provides
    @Singleton
    @MutationClient
    fun provideMutationClient(
        baseClient: OkHttpClient,
        cookieJar: CookieJar,
    ): OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    @AnonymousClient
    fun provideAnonymousClient(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    /**
     * Image-host client (#459). Reuses the base client (proxy + user-agent) but strips cookies so
     * the HFR session is never sent to imgur / diberie, and lengthens the write/call timeouts:
     * [HfrConstants.WriteTimeout] / [HfrConstants.CallTimeout] are dimensioned for short urlencoded
     * `FormBody` POSTs, not a multipart binary up to 20 MB. Connection retries are disabled because
     * an upload must not be replayed after the host may already have stored it.
     */
    @Provides
    @Singleton
    @UploadClient
    fun provideUploadClient(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .retryOnConnectionFailure(false)
        .writeTimeout(Duration.ofSeconds(UPLOAD_WRITE_TIMEOUT_SECONDS))
        .callTimeout(Duration.ofSeconds(UPLOAD_CALL_TIMEOUT_SECONDS))
        .build()

    private const val UPLOAD_WRITE_TIMEOUT_SECONDS = 60L
    private const val UPLOAD_CALL_TIMEOUT_SECONDS = 90L
}
