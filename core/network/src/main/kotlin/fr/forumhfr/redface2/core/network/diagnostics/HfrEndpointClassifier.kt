package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import okhttp3.HttpUrl

/** Pure allowlist projection. Neither the URL nor any unknown path/query value is returned. */
fun classifyHfrEndpoint(url: HttpUrl): HfrEndpoint? {
    if (url.host != "forum.hardware.fr") return null
    val page = url.queryParameter("page")?.toIntOrNull()?.takeIf { it > 0 }
    return when (url.encodedPath) {
        "/webservices/rest_api.php" -> classifyRest(url.queryParameter("uri"), page)
        "/forum1.php", "/forum1f.php" -> HfrEndpoint(HfrEndpoint.Family.FORUM1, publicCategory(url), page)
        "/forum2.php" -> HfrEndpoint(HfrEndpoint.Family.FORUM2, publicCategory(url), page)
        else -> if (isImagePath(url)) null else HfrEndpoint(HfrEndpoint.Family.HTML_OTHER)
    }
}

private fun isImagePath(url: HttpUrl): Boolean = url.encodedPath.startsWith("/images/") ||
    url.encodedPath.startsWith("/mesimages/") ||
    url.encodedPath.substringAfterLast('.').lowercase() in setOf("gif", "jpg", "jpeg", "png", "webp", "svg", "avif", "ico")

private fun publicCategory(url: HttpUrl): Int? =
    url.queryParameter("cat")?.toIntOrNull()?.takeIf { it > 0 }

private fun classifyRest(uri: String?, page: Int?): HfrEndpoint {
    val parts = uri?.trim('/')?.split('/').orEmpty()
    val prefix = parts.take(3) == listOf("forums", "hardwarefr", "categories")
    val category = if (prefix) parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 } else null
    val tail = if (category != null) parts.drop(4) else emptyList()
    val family = when {
        prefix && parts.size == 3 -> HfrEndpoint.Family.CATEGORIES
        category != null && tail == listOf("subcategories") -> HfrEndpoint.Family.SUBCATEGORIES
        category != null -> classifyTopicTail(tail)
        else -> HfrEndpoint.Family.REST_OTHER
    }
    return HfrEndpoint(family, category, page)
}

private fun classifyTopicTail(tail: List<String>): HfrEndpoint.Family {
    val topics = if (tail.size == 4 && tail[0] == "subcategories" && tail[1].toIntOrNull() != null) {
        tail.drop(2)
    } else {
        tail
    }
    if (topics.size != 2 || topics[0] != "topics") return HfrEndpoint.Family.REST_OTHER
    return when (topics[1]) {
        "participated" -> HfrEndpoint.Family.PARTICIPATED
        "read" -> HfrEndpoint.Family.READ
        "favorites" -> HfrEndpoint.Family.FAVORITES
        "last" -> HfrEndpoint.Family.LAST
        else -> HfrEndpoint.Family.REST_OTHER
    }
}
