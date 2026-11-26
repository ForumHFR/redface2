package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionKey
import fr.forumhfr.redface2.core.domain.stats.NetworkDimensions
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestKey
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.domain.stats.NoRequestKey
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "fr-rFR")
class NetworkStatsSummaryTest {
    @Test
    fun `summary contains only day blocks with separate traffic and aggregate flag ratios`() {
        val dimensions = NetworkDimensions(
            72, NetworkTarget.REST_GLOBAL_READ, NetworkRole.AUTH, AppVisibility.FOREGROUND,
            NetworkTrigger.PULL, NetworkKind.WIFI,
        )
        val latest = NetworkStatsDay(
            LocalDate.of(2026, 11, 27),
            NetworkStatsTotals(
                requests = mapOf(
                    NetworkRequestKey(dimensions, NetworkStatus.FORBIDDEN) to NetworkRequestCounts(5, 203),
                    NetworkRequestKey(
                        dimensions.copy(target = NetworkTarget.PROBE, trigger = NetworkTrigger.NONE),
                        NetworkStatus.RATE_LIMITED,
                    ) to NetworkRequestCounts(4, 10),
                ),
                noRequests = mapOf(NoRequestKey(dimensions, NoRequestReason.DNS) to 9),
                actions = mapOf(NetworkActionKey(72, NetworkAction.FLAG_REFRESH_ADMITTED, NetworkTrigger.PULL) to 2),
            ),
        )
        val older = NetworkStatsDay(
            latest.dayUtc.minusDays(1),
            NetworkStatsTotals(actions = mapOf(NetworkActionKey(71, NetworkAction.LIST) to 1)),
        )

        val blocks = RuntimeEnvironment.getApplication().resources
            .formatNetworkStatsSummary(listOf(older, latest)).split("\n\n")
        assertEquals(listOf("2026-11-27", "2026-11-26"), blocks.map { it.lineSequence().first() })
        assertTrue(blocks[0].contains("Versions (codes de build) : 72"))
        assertTrue(blocks[1].contains("Versions (codes de build) : 71"))
        assertTrue(blocks[0].contains("API : 5"))
        assertTrue(blocks[0].contains("Sonde : 4"))
        assertTrue(blocks[0].contains("403 : 5 · 429 : 4 · 5xx : 0"))
        assertTrue(blocks[0].contains("Wi-Fi : 9"))
        assertTrue(blocks[0].contains("Requêtes : 5 · Par actualisation admise : 2,5"))
        assertTrue(blocks[1].contains("Listes affichées : 1"))
        assertFalse(blocks.any { it.contains("REST_GLOBAL_READ") || it.contains("2026-11-27T") })
    }
}
