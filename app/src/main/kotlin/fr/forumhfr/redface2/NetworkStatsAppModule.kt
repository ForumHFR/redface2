package fr.forumhfr.redface2

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkStatsAppModule {
    @Provides
    @Singleton
    fun provideNetworkStatsProcess(@ApplicationContext context: Context): NetworkStatsProcess =
        NetworkStatsProcess.forApp(BuildConfig.VERSION_CODE, Application.getProcessName(), context.packageName)
}
