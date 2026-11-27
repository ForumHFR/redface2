package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.error.HfrRestRecoveryPendingException
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.network.diagnostics.HfrCallTag
import fr.forumhfr.redface2.core.network.diagnostics.withHfrDiagnostics
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
 * raises [HfrServerException] with the HTTP status and a short body excerpt for diagnostics.
 * Qualifying refusals and local cooldown rejections raise [HfrRestBlockedException].
 *
 * Authenticated calls run through [readJson] which surfaces a
 * [SessionExpiredException] when HFR redirects to login or returns the login HTML
 * payload, identical to the contract [HfrClient] already exposes for HTML pages.
 */
@Singleton
class HfrApiClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:AnonymousClient private val anonymous: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val flagReadExecutor: FlagReadExecutor = FlagReadExecutor(FlagReadLimiter(), FlagReadJitter()),
    private val breaker: HfrRestCircuitBreaker = HfrRestCircuitBreaker.inMemory(),
) {

    suspend fun getCategories(useAuth: Boolean = false): String {
        val flagsCatalogue = currentCoroutineContext()[FlagReadSession] != null
        val authenticatedRecovery = flagsCatalogue && breaker.needsAuthenticatedCatalogue()
        return get(uri = CATEGORIES_URI, useAuth = useAuth || authenticatedRecovery,
            flagOperation = FlagReadOperation.CATALOGUE)
    }

    /** Grouped-by-category envelope; this dedicated URI is never decoded as a flat topic list. */
    suspend fun getGlobalFlagTopics(bucket: HfrRestFlagBucket): String = get(
        uri = "forums/hardwarefr/topics/${bucket.uriSegment}/",
        useAuth = true,
        flagOperation = FlagReadOperation.GLOBAL_BUCKET,
    )

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
            flagOperation = FlagReadOperation.STICKY_LAST,
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
     * Per-(sub)category drapeaux endpoint :
     * `forums/hardwarefr/categories/{cat}/topics/{bucket}/` (whole category) or
     * `forums/hardwarefr/categories/{cat}/subcategories/{subcat}/topics/{bucket}/` when
     * [subcat] is non-null. This is the REST equivalent of the web `owntopic=1|2|3` filter
     * (#455): the server returns ONLY the user's flagged topics of that (sub)category, same
     * `[RestListEnvelope]<RestTopic>` shape as the regular listing — contract proven live and
     * by `rest_cat23_participated.json` / `rest_cat13_participated_favorites.json`. The flag
     * buckets are not really paginated server-side (the whole bucket comes back in one page),
     * but [page] / [resultsPerPage] are still sent for symmetry with [getTopicList].
     *
     * Requires authentication — HFR redirects an anonymous request to login, surfaced as
     * `SessionExpiredException`. `useAuth` defaults to `true` because a flags listing is by
     * definition per-user. The bucket is taken from the [HfrRestFlagBucket] enum — no
     * free-form string variant.
     *
     * The matching grouped global endpoint has a dedicated [getGlobalFlagTopics] method.
     */
    // 6 params: every one maps to a distinct REST URI segment / query param (cat, subcat,
    // bucket, page, results_per_page) plus the auth toggle. They are not a cohesive object
    // and bundling them would obscure the URL contract. Suppressing locally (#455) rather
    // than relaxing the project-wide threshold.
    @Suppress("LongParameterList")
    suspend fun getCategoryFlagTopics(
        cat: Int,
        bucket: HfrRestFlagBucket,
        subcat: Int? = null,
        page: Int = 1,
        resultsPerPage: Int = DEFAULT_RESULTS_PER_PAGE,
        useAuth: Boolean = true,
    ): String {
        require(page >= 1) { "page must be >= 1, got $page" }
        require(resultsPerPage in 1..MAX_RESULTS_PER_PAGE) {
            "resultsPerPage must be in 1..$MAX_RESULTS_PER_PAGE, got $resultsPerPage"
        }
        val uri = if (subcat == null) {
            "${CATEGORIES_URI}$cat/topics/${bucket.uriSegment}/"
        } else {
            "${CATEGORIES_URI}$cat/subcategories/$subcat/topics/${bucket.uriSegment}/"
        }
        return get(
            uri = uri,
            useAuth = useAuth,
            flagOperation = FlagReadOperation.CATEGORY_BUCKET,
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

    private val authenticatedRest = withExchangeGuard(authenticated)
    private val anonymousRest = withExchangeGuard(anonymous)

    private fun withExchangeGuard(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .addNetworkInterceptor { chain ->
            val attempt = checkNotNull(chain.call().request().tag(RestAttempt::class.java))
            attempt.session?.checkNotBlocked()
            breaker.checkExchange(checkNotNull(attempt.admission), attempt.session?.singleExchange == true)
            chain.proceed(chain.request())
        }
        .build()

    private class RestAttempt(val session: FlagReadSession?) {
        @Volatile var admission: HfrRestCircuitBreaker.Admission? = null
    }

    private suspend fun get(
        uri: String,
        useAuth: Boolean,
        extraParams: Map<String, String> = emptyMap(),
        flagOperation: FlagReadOperation? = null,
    ): String {
        val builder = baseUrl.newBuilder()
            .encodedPath(REST_API_PATH)
            .setEncodedQueryParameter(PARAM_URI, uri)
        extraParams.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        val url = builder.build()
        val coroutine = currentCoroutineContext()
        val session = coroutine[FlagReadSession]
        val attempt = RestAttempt(session)
        val request = Request.Builder().url(url).get().tag(RestAttempt::class.java, attempt)
            .build().withHfrDiagnostics(coroutine)
        val client = if (useAuth) authenticatedRest else anonymousRest
        return if (session == null || flagOperation == null) {
            client.newCall(request).executeCancellable(useAuth)
        } else {
            flagReadExecutor.execute(client, request, flagOperation, session) { call, admitted ->
                call.executeCancellable(useAuth, admitted)
            }
        }
    }

    /** Keep the cancellation bridge alive through both headers and body, as in [HfrClient]. */
    private suspend fun Call.executeCancellable(
        useAuth: Boolean,
        admitted: () -> Unit = {},
    ): String = withContext(ioDispatcher) {
        val call = this@executeCancellable
        val session = currentCoroutineContext()[FlagReadSession]
        session?.checkNotBlocked()
        session?.checkBudget()
        val context = if (useAuth) HfrRestContext.AUTHENTICATED else HfrRestContext.ANONYMOUS
        val admission = try {
            breaker.admit(context)
        } catch (blocked: HfrRestBlockedException) {
            session?.abort(blocked)
            throw blocked
        } catch (pending: HfrRestRecoveryPendingException) {
            session?.abort(pending)
            throw pending
        }
        var reply: RestReply? = null
        try {
            checkNotNull(call.request().tag(RestAttempt::class.java)).admission = admission
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                continuation.resumeWith(runCatching {
                    session?.checkNotBlocked()
                    session?.checkBudget()
                    admitted()
                    call.request().tag(HfrCallTag::class.java)?.executing()
                    call.execute().use { response ->
                        val metadata = response.restReply()
                        if (metadata?.qualifying == true) {
                            reply = metadata
                            throw session.blocked(response.code)
                        }
                        val body = response.body.string()
                        val classified = response.restReply(body)
                        if (classified?.qualifying == true) {
                            reply = classified
                            throw session.blocked(response.code)
                        }
                        readJson(response, body, useAuth).also {
                            response.checkRecoveryContext(admission)
                            reply = classified
                        }
                    }
                })
            }
        } catch (blocked: HfrRestBlockedException) {
            session?.abort(blocked)
            throw blocked
        } catch (error: IOException) {
            if (!admission.isRecovery) throw error
            // A recovery whose only exchange failed must not take the executor's retry path.
            val failure = when (error) {
                is SessionExpiredException, is HfrRestRecoveryPendingException -> error
                else -> HfrRestRecoveryPendingException()
            }
            session?.abort(failure)
            throw failure
        } finally {
            withContext(NonCancellable) { breaker.complete(admission, reply) }
        }
    }

    private fun FlagReadSession?.blocked(code: Int): HfrRestBlockedException =
        HfrRestBlockedException(code).also { this?.abort(it) }

    private fun Response.checkRecoveryContext(admission: HfrRestCircuitBreaker.Admission) {
        if (!admission.isRecovery) return
        // OkHttp's outer response request predates cookie selection. Use the wire request.
        val exchange = networkResponse?.takeIf { it.isRestReply && it.isSuccessful }
        val expectsAuthentication = admission.context == HfrRestContext.AUTHENTICATED
        if (exchange?.request?.hasHfrSession() != expectsAuthentication) {
            throw HfrRestRecoveryPendingException()
        }
    }

    private fun Request.hasHfrSession(): Boolean {
        val cookieNames = headers.values("Cookie").flatMap { it.split(';') }.mapNotNull { entry ->
            val cookie = entry.trim()
            cookie.substringBefore('=').takeIf { cookie.substringAfter('=', "").isNotBlank() }
        }
        // Same session marker as login and the editor guard: HFR's password cookie is `md_passs`, not
        // `md_pass`, so requiring a second name here would never confirm a real authenticated recovery.
        return "md_user" in cookieNames
    }

    private fun readJson(response: Response, body: String, useAuth: Boolean): String {
        if (!response.isSuccessful) {
            throw HfrServerException(
                code = response.code,
                url = response.request.url.toString(),
                detailMessage = failureMessage(response.code, response.request.url, body),
            )
        }
        val finalUrl = response.request.url
        if (useAuth && (finalUrl.isLoginUrl() || body.looksLikeLoginPage())) {
            throw SessionExpiredException(finalUrl.toString())
        }
        return body
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
