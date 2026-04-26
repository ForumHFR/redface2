package fr.forumhfr.redface2.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import javax.inject.Singleton
import okhttp3.CookieJar
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideBaseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HfrConstants.ConnectTimeout)
        .readTimeout(HfrConstants.ReadTimeout)
        .writeTimeout(HfrConstants.WriteTimeout)
        .callTimeout(HfrConstants.CallTimeout)
        .addInterceptor(UserAgentInterceptor(HfrConstants.USER_AGENT))
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
    @AnonymousClient
    fun provideAnonymousClient(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()
}
