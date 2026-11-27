package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import okhttp3.HttpUrl

internal object NetworkStatsClassifier {
    private const val FORUM_HOST = "forum.hardware.fr"
    private const val FORUM_IMAGES_HOST = "forum-images.hardware.fr"

    fun target(url: HttpUrl, role: NetworkRole): NetworkTarget = when {
        role == NetworkRole.PROBE -> NetworkTarget.PROBE
        url.host == FORUM_IMAGES_HOST -> NetworkTarget.FORUM_IMAGES
        url.host == FORUM_HOST -> forumTarget(url)
        role == NetworkRole.UPLOAD -> NetworkTarget.THIRD_PARTY_UPLOAD
        role == NetworkRole.IMAGE || isImagePath(url) -> NetworkTarget.THIRD_PARTY_IMAGES
        else -> NetworkTarget.OTHER_HOST
    }

    fun peakGroup(url: HttpUrl, role: NetworkRole): NetworkPeakGroup? = when {
        role == NetworkRole.PROBE -> NetworkPeakGroup.PROBE
        url.host == FORUM_IMAGES_HOST -> NetworkPeakGroup.FORUM_IMAGES
        url.host == FORUM_HOST -> NetworkPeakGroup.FORUM
        else -> null
    }

    private fun forumTarget(url: HttpUrl): NetworkTarget = endpointOverride(url.encodedPath) ?: classifyForum(url)

    private fun endpointOverride(path: String): NetworkTarget? = when (path) {
        "/user/supprperso.php", "/user/addperso.php", "/user/supprperso_validation.php" ->
            NetworkTarget.FAVORITE_SMILEYS
        "/message.php", "/bdd.php", "/bddpost.php", "/user/modo.php", "/user/vote.php",
        "/close_sondage.php", "/addflag.php", "/delflag.php" -> NetworkTarget.HTML_WRITE
        else -> null
    }

    private fun classifyForum(url: HttpUrl): NetworkTarget = when (classifyHfrEndpoint(url)?.family) {
        HfrEndpoint.Family.CATEGORIES -> NetworkTarget.REST_CATALOGUE
        HfrEndpoint.Family.SUBCATEGORIES -> NetworkTarget.REST_SUBCATEGORIES
        HfrEndpoint.Family.PARTICIPATED -> NetworkTarget.REST_CATEGORY_PARTICIPATED
        HfrEndpoint.Family.READ -> NetworkTarget.REST_CATEGORY_READ
        HfrEndpoint.Family.FAVORITES -> NetworkTarget.REST_CATEGORY_FAVORITES
        HfrEndpoint.Family.LAST -> NetworkTarget.REST_CATEGORY_LAST
        HfrEndpoint.Family.REST_OTHER -> otherRest(url)
        HfrEndpoint.Family.FORUM1 -> NetworkTarget.HTML_FORUM1
        HfrEndpoint.Family.FORUM2 -> NetworkTarget.HTML_FORUM2
        HfrEndpoint.Family.MP_LIST -> NetworkTarget.HTML_MP_LIST
        HfrEndpoint.Family.HTML_OTHER -> NetworkTarget.HTML_OTHER
        null -> NetworkTarget.FORUM_IMAGES
    }

    private fun otherRest(url: HttpUrl): NetworkTarget = when (url.queryParameter("uri")?.trim('/')) {
        "forums/hardwarefr/topics/participated" -> NetworkTarget.REST_GLOBAL_PARTICIPATED
        "forums/hardwarefr/topics/read" -> NetworkTarget.REST_GLOBAL_READ
        "forums/hardwarefr/topics/favorites" -> NetworkTarget.REST_GLOBAL_FAVORITES
        else -> NetworkTarget.REST_OTHER
    }
}
