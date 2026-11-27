package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionKey
import fr.forumhfr.redface2.core.domain.stats.NetworkDimensions
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakKey
import fr.forumhfr.redface2.core.domain.stats.NetworkPeaks
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
                    NetworkRequestKey(dimensions.copy(trigger = NetworkTrigger.NONE), NetworkStatus.NO_RESPONSE) to
                        NetworkRequestCounts(2),
                    NetworkRequestKey(
                        dimensions.copy(target = NetworkTarget.FORUM_IMAGES, trigger = NetworkTrigger.NONE),
                        NetworkStatus.FORBIDDEN,
                    ) to NetworkRequestCounts(80),
                    NetworkRequestKey(
                        dimensions.copy(target = NetworkTarget.PROBE, trigger = NetworkTrigger.NONE),
                        NetworkStatus.RATE_LIMITED,
                    ) to NetworkRequestCounts(4, 10),
                ),
                noRequests = mapOf(
                    NoRequestKey(dimensions, NoRequestReason.DNS) to 9,
                    NoRequestKey(dimensions.copy(network = NetworkKind.CELLULAR), NoRequestReason.TCP_TIMEOUT) to 2,
                    NoRequestKey(dimensions, NoRequestReason.PROXY_TIMEOUT) to 1,
                    NoRequestKey(dimensions, NoRequestReason.CANCELLED) to 50,
                    NoRequestKey(dimensions.copy(target = NetworkTarget.THIRD_PARTY_IMAGES), NoRequestReason.DNS) to 90,
                ),
                peaks = mapOf(
                    NetworkPeakKey(72, NetworkPeakGroup.FORUM) to NetworkPeaks(1, 2, 3),
                    NetworkPeakKey(72, NetworkPeakGroup.PROBE) to NetworkPeaks(4, 5, 6),
                ),
                actions = mapOf(
                    NetworkActionKey(72, NetworkAction.FLAG_REFRESH_ADMITTED, NetworkTrigger.PULL) to 2,
                    NetworkActionKey(72, NetworkAction.FLAG_REFRESH_IGNORED, NetworkTrigger.TOPIC_RECONCILE) to 1,
                ),
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
        assertEquals(7L, latest.totals.forumRequests())
        assertTrue(blocks[0].contains("API : 7"))
        assertTrue(blocks[0].contains("Sonde : 4"))
        assertTrue(blocks[0].contains("403 : 5 · 429 : 0 · 5xx : 0"))
        assertTrue(blocks[0].contains("Requêtes sans réponse des pages et API de forum.hardware.fr : 2"))
        assertTrue(blocks[0].contains("DNS : Wi-Fi 9 · Mobile 0"))
        assertTrue(blocks[0].contains("Annulation : Wi-Fi 50 · Mobile 0"))
        assertTrue(blocks[0].contains("Délai de connexion TCP dépassé : Wi-Fi 0 · Mobile 2"))
        assertTrue(blocks[0].contains("Délai dépassé avant TLS via proxy HTTP : Wi-Fi 1 · Mobile 0"))
        assertTrue(blocks[0].contains("Forum — Par seconde : 1 · Par minute : 2 · En vol : 3"))
        assertTrue(blocks[0].contains("Sonde — Par seconde : 4 · Par minute : 5 · En vol : 6"))
        assertTrue(blocks[0].contains("Requêtes : 5 · Par actualisation admise : 2,5"))
        assertTrue(blocks[0].contains("Actualisation ciblée après lecture"))
        assertTrue(blocks[1].contains("Listes affichées : 1"))
        assertFalse(blocks.any { it.contains("REST_GLOBAL_READ") || it.contains("2026-11-27T") })
    }
}
