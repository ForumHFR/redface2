package fr.forumhfr.redface2.core.network.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.network.diagnostics.AndroidNetworkDiagnosticsContextProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun provideDiagnosticsClock(): DiagnosticsClock = DiagnosticsClock(SystemClock::elapsedRealtimeNanos)

    @Provides
    @Singleton
    fun provideNetworkDiagnosticsContext(
        provider: AndroidNetworkDiagnosticsContextProvider,
    ): NetworkDiagnosticsContextProvider = provider
}
