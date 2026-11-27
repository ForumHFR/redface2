package fr.forumhfr.redface2.core.network.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import fr.forumhfr.redface2.core.network.di.NetworkClientListeners
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import fr.forumhfr.redface2.core.network.diagnostics.NetworkStatsListenerFactory
import java.time.Clock

internal fun disabledStatsFactory() = NetworkStatsListenerFactory(
    NetworkStatsBuffer(NetworkStatsProcess(1, isMain = false)),
    AppForegroundDiagnostics(DiagnosticsLog()),
    Clock.systemUTC(),
)

internal fun testClientListeners(
    diagnostics: HfrEventListenerFactory = HfrEventListenerFactory(HfrNetworkDiagnostics(DiagnosticsLog())),
) = NetworkClientListeners(diagnostics, disabledStatsFactory())
