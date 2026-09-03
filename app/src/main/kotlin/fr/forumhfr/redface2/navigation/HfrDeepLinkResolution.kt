package fr.forumhfr.redface2.navigation

import android.content.Intent
import android.net.Uri
import fr.forumhfr.redface2.core.parser.url.HfrTopicUrlParser

internal sealed interface HfrDeepLinkResolution {
    data class Route(val parsed: ParsedDeepLink) : HfrDeepLinkResolution

    data class BrowserFallback(val uri: Uri) : HfrDeepLinkResolution

    data object Ignore : HfrDeepLinkResolution
}

@Suppress("ReturnCount") // Ignore-guards (data/scheme/action/host) + legacy route + pretty-url route/fallback.
internal fun resolveHfrDeepLink(intent: Intent): HfrDeepLinkResolution {
    val uri = intent.data ?: return HfrDeepLinkResolution.Ignore
    if (
        intent.action != Intent.ACTION_VIEW ||
        !isSupportedHfrUri(uri)
    ) {
        return HfrDeepLinkResolution.Ignore
    }

    return resolveHfrUri(uri)
}

@Suppress("ReturnCount") // Ignore-guards + legacy route + pretty-url route/fallback.
internal fun resolveHfrUri(uri: Uri): HfrDeepLinkResolution {
    if (!isSupportedHfrUri(uri)) return HfrDeepLinkResolution.Ignore

    parseHfrDeepLink(uri)?.let { return HfrDeepLinkResolution.Route(it) }

    val topicUrl = HfrTopicUrlParser.parse(uri.toString())
        ?: return HfrDeepLinkResolution.BrowserFallback(uri)
    val cat = HfrCategorySlugMap.categoryIdFor(topicUrl.categorySlug)
        ?: return HfrDeepLinkResolution.BrowserFallback(uri)
    return HfrDeepLinkResolution.Route(
        ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(
                cat = cat,
                post = topicUrl.post,
                page = topicUrl.page,
                scrollTo = topicUrl.scrollTo,
                resolveScrollToPage = topicUrl.scrollTo != null && topicUrl.page == 1,
            ),
        ),
    )
}

private fun isSupportedHfrUri(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    return scheme in WEB_SCHEMES &&
        uri.host?.equals(HFR_HOST, ignoreCase = true) == true
}

internal fun parseHfrDeepLink(uri: Uri): ParsedDeepLink? = when (uri.path) {
    // forum1.php is the topic-list page (per category / subcategory). Required:
    // `cat`. Optional: `subcat`, `page`. Lands on the Forum tab so the back stack
    // walks Forum -> Category -> (deeper) instead of Flags.
    "/forum1.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val subcat = uri.getQueryParameter("subcat")?.toIntOrNull()
        // Preserve `page` from the deep link so a shared link to e.g.
        // forum1.php?cat=23&subcat=550&page=2 lands on page 2 instead of silently
        // resetting to 1. Out-of-range / malformed values fall back to 1.
        val page = uri.getQueryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        ParsedDeepLink(
            destination = TopLevelDestination.Forum,
            route = CategoryRoute(cat = cat, subcat = subcat, page = page),
        )
    }

    // forum2.php is the topic-content page (the actual posts). Required: `cat`,
    // `post`. Optional: `page`, fragment `#t<numreponse>` for scroll-to-post.
    // Lands on the Flags tab — the typical reading surface.
    "/forum2.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val post = uri.getQueryParameter("post")?.toIntOrNull() ?: return null
        val page = uri.getQueryParameter("page")?.toIntOrNull() ?: 1
        // #750 — the `numreponse` QUERY param is the fallback target: HFR email-notification
        // links carry it alongside the fragment, and some mail clients strip the fragment.
        val scrollTo = uri.fragment?.removePrefix("t")?.toIntOrNull()
            ?: uri.getQueryParameter("numreponse")?.toIntOrNull()
        ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(
                cat = cat,
                post = post,
                page = page,
                scrollTo = scrollTo,
                // #750 — email links always serialise `page=1` whatever page the target post
                // lives on; a page-1 link WITH an anchor is therefore untrusted and the real
                // page is resolved before the first load. An explicit page > 1 is trusted as-is.
                resolveScrollToPage = scrollTo != null && page == 1,
            ),
        )
    }

    "/forum1f.php" -> ParsedDeepLink(
        destination = TopLevelDestination.Flags,
        route = FlagsListRoute,
    )

    else -> null
}

private val WEB_SCHEMES = setOf("http", "https")
private const val HFR_HOST = "forum.hardware.fr"
