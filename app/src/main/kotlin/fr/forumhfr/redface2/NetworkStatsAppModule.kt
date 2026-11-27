package fr.forumhfr.redface2

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import fr.forumhfr.redface2.core.network.qualifiers.HfrUserAgent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkStatsAppModule {
    @Provides
    @HfrUserAgent
    fun provideHfrUserAgent(): String = buildHfrUserAgent(BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)

    @Provides
    @Singleton
    fun provideNetworkStatsProcess(@ApplicationContext context: Context): NetworkStatsProcess =
        NetworkStatsProcess.forApp(BuildConfig.VERSION_CODE, Application.getProcessName(), context.packageName)
}

internal fun buildHfrUserAgent(version: String, flavor: String): String =
    "Redface2/${version.httpToken()} (Android; ${flavor.httpToken()}; +https://github.com/ForumHFR/redface2)"

private fun String.httpToken(): String =
    replace(Regex("[^A-Za-z0-9._+-]"), "_").ifEmpty { "unknown" }
