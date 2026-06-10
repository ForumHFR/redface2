package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * REST client for the JSON HFR API exposed at `/webservices/rest_api.php`. Phase 1C-A
 * per ADR-003: the browsing layer (categories, subcategories, topic listings, topic
 * metadata) is fed by this client. HTML scraping via [HfrClient] stays for posts, MPs,
 * mutations and login.
 *
 * URL shape: every call hits `${baseUrl}webservices/rest_api.php?uri=<path>` where the
 * `uri` value is a HATEOAS-style path (e.g. `forums/hardwarefr/categories/`). Query
 * parameters that influence the result (`page`, `results_per_page`) are sent as
 * top-level query parameters, **not** baked into the `uri` value, mirroring what the
 * server contract advertises and what the captured fixtures confirm.
 *
 * The client returns raw JSON [String]. Parsing lives in `:core:data` next to its
 * consuming repositories (per ADR-003, "Frontières de modules"). On non-2xx the call
 * raises [HfrServerException] (#324 — typed status code so the screens can tell a 5xx
 * outage from a network cut) with the URL, status and a short body excerpt for
 * diagnostics.
 *
 * Authenticated calls run through [executeAuthenticatedJson] which surfaces a
 * [SessionExpiredException] when HFR redirects to login or returns the login HTML
 * payload, identical to the contract [HfrClient] already exposes for HTML pages.
 */
@Singleton
class HfrApiClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:AnonymousClient private val anonymous: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
) {

    suspend fun getCategories(useAuth: Boolean = false): String =
        get(uri = CATEGORIES_URI, useAuth = useAuth)

    suspend fun getSubcategories(cat: Int): String =
        get(uri = "${CATEGORIES_URI}$cat/subcategories/", useAuth = false)

    /**
     * Fetches a page of topics for `cat` (and optionally `subcat`).
     *
     * `useAuth` has no default — callers must pick explicitly. Authenticated calls
     * surface per-user fields the topic listing UI relies on (`is_read`,
     * `last_post_read_id`, `links.posts.href?page=N` for the last read page),
     * while a future prefetch path that hits `/topics/last/` to warm a cache must
     * stay unauthenticated to avoid mutating the user's drapeaux state.
     */
    suspend fun getTopicList(
        cat: Int,
        subcat: Int?,
        page: Int = 1,
        resultsPerPage: Int = DEFAULT_RESULTS_PER_PAGE,
        useAuth: Boolean,
    ): String {
        require(page >= 1) { "page must be >= 1, got $page" }
        require(resultsPerPage in 1..MAX_RESULTS_PER_PAGE) {
            "resultsPerPage must be in 1..$MAX_RESULTS_PER_PAGE, got $resultsPerPage"
        }
        val uri = if (subcat == null) {
            "${CATEGORIES_URI}$cat/topics/last/"
        } else {
            "${CATEGORIES_URI}$cat/subcategories/$subcat/topics/last/"
        }
        return get(
            uri = uri,
            useAuth = useAuth,
            extraParams = mapOf(
                PARAM_PAGE to page.toString(),
                PARAM_RESULTS_PER_PAGE to resultsPerPage.toString(),
            ),
        )
    }

    suspend fun getTopicMetadata(
        cat: Int,
        topicId: Int,
        useAuth: Boolean = false,
    ): String = get(uri = "${CATEGORIES_URI}$cat/topics/$topicId/", useAuth = useAuth)

    /**
     * Per-category drapeaux endpoint :
     * `forums/hardwarefr/categories/{cat}/topics/{bucket}/`. Requires authentication —
     * HFR redirects an anonymous request to login, surfaced as `SessionExpiredException`.
     * The response is the same [RestListEnvelope]<RestTopic> shape used by the topic
     * listing, contract proven by `rest_cat23_participated.json`.
     *
     * `useAuth` defaults to `true` because a flags listing is by definition per-user.
     * The bucket is taken from the [HfrRestFlagBucket] enum — no free-form string variant.
     *
     * The matching global endpoint (`forums/hardwarefr/topics/{bucket}/`) is intentionally
     * not exposed yet : its envelope is grouped-by-category and we have no captured
     * fixture for it. It can be added in a follow-up PR once a fixture exists.
     */
    suspend fun getCategoryFlagTopics(
        cat: Int,
        bucket: HfrRestFlagBucket,
        page: Int = 1,
        resultsPerPage: Int = DEFAULT_RESULTS_PER_PAGE,
        useAuth: Boolean = true,
    ): String {
        require(page >= 1) { "page must be >= 1, got $page" }
        require(resultsPerPage in 1..MAX_RESULTS_PER_PAGE) {
            "resultsPerPage must be in 1..$MAX_RESULTS_PER_PAGE, got $resultsPerPage"
        }
        return get(
            uri = "${CATEGORIES_URI}$cat/topics/${bucket.uriSegment}/",
            useAuth = useAuth,
            extraParams = mapOf(
                PARAM_PAGE to page.toString(),
                PARAM_RESULTS_PER_PAGE to resultsPerPage.toString(),
            ),
        )
    }

    /**
     * Rewrites a HATEOAS `href` returned by the server (`https://forum.hardware.fr/api/...`)
     * into the actual callable URL on HFR (`/webservices/rest_api.php?uri=...`). Apache on
     * forum.hardware.fr never had the `/api/` rewrite enabled, so any href consumed without
     * this transformation 404s. The rewrite is intentionally strict: only the canonical
     * host + scheme + `/api/` path prefix are accepted, anything else throws.
     *
     * Phase 1C-A keeps this helper around but largely **unused** in production — the
     * mappers are happy to read query params off the raw href string (e.g.
     * `?page=N&results_per_page=M` for the topic listing) and the four high-level
     * `getXxx(...)` methods build URIs deterministically from `cat`/`subcat`. The
     * helper exists for the next slice that follows a HATEOAS link verbatim
     * (e.g. derefing `links.posts.href` to load the actual posts page in REST).
     *
     * @throws IllegalArgumentException if [href] is not a valid HFR HATEOAS link.
     */
    fun rewriteHateoasHref(href: HttpUrl): HttpUrl {
        require(href.scheme == HFR_SCHEME) {
            "Unsupported scheme: ${href.scheme}, expected $HFR_SCHEME"
        }
        require(href.host == HFR_HOST) {
            "Unsupported host: ${href.host}, expected $HFR_HOST"
        }
        require(href.encodedPath.startsWith(HFR_API_PREFIX)) {
            "Unsupported path: ${href.encodedPath}, expected to start with $HFR_API_PREFIX"
        }
        val tail = href.encodedPath.removePrefix(HFR_API_PREFIX)
        // Start the builder from `href` to preserve scheme + host (always
        // forum.hardware.fr / https here per the validations above), independently of
        // any test-time `baseUrl` override pointing to a MockWebServer. The rewrite is
        // a pure transformation: HATEOAS URL on HFR -> callable URL on HFR.
        val builder = href.newBuilder()
            .encodedPath(REST_API_PATH)
            .setEncodedQueryParameter(PARAM_URI, tail)
        for (i in 0 until href.querySize) {
            val name = href.queryParameterName(i)
            val value = href.queryParameterValue(i)
            if (name != PARAM_URI && value != null) {
                builder.addQueryParameter(name, value)
            }
        }
        return builder.build()
    }

    private suspend fun get(
        uri: String,
        useAuth: Boolean,
        extraParams: Map<String, String> = emptyMap(),
    ): String {
        val builder = baseUrl.newBuilder()
            .encodedPath(REST_API_PATH)
            .setEncodedQueryParameter(PARAM_URI, uri)
        extraParams.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        val url = builder.build()
        val request = Request.Builder().url(url).get().build()
        val client = if (useAuth) authenticated else anonymous
        return if (useAuth) {
            client.newCall(request).executeAuthenticatedJson()
        } else {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // #324 — typed (status code) while keeping the richer REST diagnostic line.
                    throw HfrServerException(
                        code = response.code,
                        url = url.toString(),
                        detailMessage = failureMessage(response.code, url, response.body.string()),
                    )
                }
                response.body.string()
            }
        }
    }

    private fun Call.executeAuthenticatedJson(): String = execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            // #324 — typed (status code) while keeping the richer REST diagnostic line.
            throw HfrServerException(
                code = response.code,
                url = response.request.url.toString(),
                detailMessage = failureMessage(response.code, response.request.url, body),
            )
        }
        val finalUrl = response.request.url
        if (finalUrl.isLoginUrl() || body.looksLikeLoginPage()) {
            throw SessionExpiredException(finalUrl.toString())
        }
        body
    }

    private fun failureMessage(code: Int, url: HttpUrl, body: String): String {
        val excerpt = body.take(BODY_EXCERPT_CHARS).replace('\n', ' ')
        return "HFR REST returned $code for $url — body[0..${excerpt.length}]: $excerpt"
    }

    private fun HttpUrl.isLoginUrl(): Boolean =
        encodedPath.endsWith("/login.php") || encodedPath.endsWith("/login_validation.php")

    private fun String.looksLikeLoginPage(): Boolean {
        val lower = lowercase()
        return "login_validation.php" in lower &&
            "name=\"pseudo\"" in lower &&
            "name=\"password\"" in lower
    }

    private companion object {
        const val HFR_SCHEME = "https"
        const val HFR_HOST = "forum.hardware.fr"
        const val HFR_API_PREFIX = "/api/"
        const val REST_API_PATH = "/webservices/rest_api.php"
        const val CATEGORIES_URI = "forums/hardwarefr/categories/"
        const val PARAM_URI = "uri"
        const val PARAM_PAGE = "page"
        const val PARAM_RESULTS_PER_PAGE = "results_per_page"
        const val DEFAULT_RESULTS_PER_PAGE = 50
        const val MAX_RESULTS_PER_PAGE = 100
        const val BODY_EXCERPT_CHARS = 240
    }
}
