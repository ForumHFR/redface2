package fr.forumhfr.redface2.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.network.EditorWriteOwnerInterceptor
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.HfrRestCircuitBreaker
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import fr.forumhfr.redface2.core.network.diagnostics.NetworkStatsListenerFactory
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import fr.forumhfr.redface2.core.network.qualifiers.HfrUserAgent
import fr.forumhfr.redface2.core.network.qualifiers.ImageClient
import fr.forumhfr.redface2.core.network.qualifiers.MutationClient
import fr.forumhfr.redface2.core.network.qualifiers.UploadClient
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.CookieJar
import okhttp3.EventListener
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
    fun provideHfrEventListenerFactory(diagnostics: HfrNetworkDiagnostics): HfrEventListenerFactory =
        HfrEventListenerFactory(diagnostics)

    @Provides
    @Singleton
    fun provideBaseClient(
        userPreferencesRepository: UserPreferencesRepository,
        listeners: NetworkClientListeners,
        @HfrUserAgent userAgent: String,
    ): OkHttpClient =
        OkHttpClient.Builder()
        .eventListenerFactory(listeners.hfr(HfrClientRole.ANON))
        .connectTimeout(HfrConstants.ConnectTimeout)
        .readTimeout(HfrConstants.ReadTimeout)
        .writeTimeout(HfrConstants.WriteTimeout)
        .callTimeout(HfrConstants.CallTimeout)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .applyProxyConfig(userPreferencesRepository.readProxyConfigForNetworkBootstrap())
        .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedClient(
        baseClient: OkHttpClient,
        cookieJar: CookieJar,
        listeners: NetworkClientListeners,
    ): OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory(listeners.hfr(HfrClientRole.AUTH))
        .cookieJar(cookieJar)
        .followSslRedirects(false)
        .build()

    @Provides
    @Singleton
    @MutationClient
    fun provideMutationClient(
        baseClient: OkHttpClient,
        cookieJar: CookieJar,
        listeners: NetworkClientListeners,
    ): OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory(listeners.hfr(HfrClientRole.MUTATION))
        .cookieJar(cookieJar)
        .addNetworkInterceptor(EditorWriteOwnerInterceptor())
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    @AnonymousClient
    fun provideAnonymousClient(
        baseClient: OkHttpClient,
        listeners: NetworkClientListeners,
    ): OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory(listeners.hfr(HfrClientRole.ANON))
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /**
     * Cookie-less image client shared by Coil and gallery saves. Unlike every HFR client, it may
     * follow HTTPS-to-HTTP redirects because legacy image hosts still require that compatibility.
     */
    @Provides
    @Singleton
    @ImageClient
    fun provideImageClient(
        baseClient: OkHttpClient,
        listeners: NetworkClientListeners,
    ): OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory(listeners.images())
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(RehostHttpsUpgradeInterceptor())
        .addInterceptor(RefererInterceptor())
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
    fun provideUploadClient(
        baseClient: OkHttpClient,
        listeners: NetworkClientListeners,
    ): OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory(listeners.hfr(HfrClientRole.UPLOAD))
        .cookieJar(CookieJar.NO_COOKIES)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .writeTimeout(Duration.ofSeconds(UPLOAD_WRITE_TIMEOUT_SECONDS))
        .callTimeout(Duration.ofSeconds(UPLOAD_CALL_TIMEOUT_SECONDS))
        .build()

    private const val UPLOAD_WRITE_TIMEOUT_SECONDS = 60L
    private const val UPLOAD_CALL_TIMEOUT_SECONDS = 90L
}

class NetworkClientListeners @Inject constructor(
    private val diagnostics: HfrEventListenerFactory,
    private val stats: NetworkStatsListenerFactory,
) {
    fun hfr(role: HfrClientRole): EventListener.Factory = stats.forRole(
        when (role) {
            HfrClientRole.AUTH -> NetworkRole.AUTH
            HfrClientRole.ANON -> NetworkRole.ANON
            HfrClientRole.MUTATION -> NetworkRole.MUTATION
            HfrClientRole.UPLOAD -> NetworkRole.UPLOAD
        },
        diagnostics.withRole(role),
    )

    fun images(): EventListener.Factory = stats.forRole(NetworkRole.IMAGE, diagnostics.forImages())
}

@Module
@InstallIn(SingletonComponent::class)
interface HfrRestAvailabilityModule {
    @Binds
    fun bindHfrRestAvailability(impl: HfrRestCircuitBreaker): HfrRestAvailability
}
