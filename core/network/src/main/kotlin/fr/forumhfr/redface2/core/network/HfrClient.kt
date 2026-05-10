package fr.forumhfr.redface2.core.network

import androidx.tracing.trace
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class HfrClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:AnonymousClient private val anonymous: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
) {
    suspend fun getTopicPage(
        cat: Int,
        post: Int,
        page: Int,
        useAuth: Boolean = true,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum2.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .addQueryParameter("page", page.toString())
            .build()

        val request = Request.Builder().url(url).get().build()
        return if (useAuth) {
            // Authenticated read: an expired session would otherwise be parsed silently as an
            // empty topic. executeAuthenticatedHtml() raises SessionExpiredException so the
            // caller can surface a reconnect CTA instead of a misleading empty screen. The
            // `rf2.topic` prefix scopes the trace sections to the topic feature only — other
            // callers (MP list, …) opt out of tracing by omitting the prefix.
            authenticated.newCall(request).executeAuthenticatedHtml(tracePrefix = TOPIC_TRACE_PREFIX)
        } else {
            // `rf2.topic.network` covers DNS + connect + TLS + headers (the part OkHttp returns
            // before we touch the body). `rf2.topic.body_read` covers the bytes-on-the-wire
            // pull from the response body. Splitting them lets a profiler tell a slow handshake
            // apart from a slow body download. The auth branch above wires the same prefix into
            // `executeAuthenticatedHtml`.
            trace("$TOPIC_TRACE_PREFIX.network") {
                anonymous.newCall(request).execute()
            }.use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HFR returned ${response.code} for $url")
                }
                trace("$TOPIC_TRACE_PREFIX.body_read") { response.body.string() }
            }
        }
    }

    /**
     * Fetches the authenticated MP list page. The endpoint is the legacy v1 URL
     * (`forum1.php?config=hfr.inc&cat=prive&...`), which is structurally a topic listing
     * scoped to the user's private inbox. Always uses the authenticated client — there is
     * no anonymous variant of this page (HFR redirects to login).
     */
    suspend fun getPrivateMessageListPage(page: Int = 1): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum1.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", "prive")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("subcat", "")
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("trash", "0")
            .addQueryParameter("trash_post", "0")
            .addQueryParameter("moderation", "0")
            .addQueryParameter("new", "0")
            .addQueryParameter("nojs", "0")
            .addQueryParameter("subcatgroup", "0")
            .build()

        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Executes the call, returns the body as a UTF-8 string, and raises
     * [SessionExpiredException] if HFR redirected to the login page or returned the login form
     * inline. When [tracePrefix] is non-null, the OkHttp call up to headers is wrapped in
     * `<prefix>.network` and the body pull in `<prefix>.body_read` (cf. `docs/guides/profiling.md`).
     * Callers that don't belong to the topic parcours pass `null` to stay out of `rf2.topic.*`.
     */
    private fun Call.executeAuthenticatedHtml(tracePrefix: String? = null): String {
        val response: Response = if (tracePrefix != null) {
            trace("$tracePrefix.network") { execute() }
        } else {
            execute()
        }
        return response.use {
            if (!response.isSuccessful) {
                throw IOException("HFR returned ${response.code} for ${response.request.url}")
            }
            val html = if (tracePrefix != null) {
                // Session-expiry detection (login redirect / login form sniff) runs after the body
                // is in memory, so its cost is negligible relative to body_read; not worth a third
                // section.
                trace("$tracePrefix.body_read") { response.body.string() }
            } else {
                response.body.string()
            }
            val finalUrl = response.request.url
            if (finalUrl.isLoginUrl() || html.looksLikeLoginPage()) {
                throw SessionExpiredException(finalUrl.toString())
            }
            html
        }
    }

    private companion object {
        // Prefix consumed by `docs/guides/profiling.md` — keep in lockstep with the catalogue.
        private const val TOPIC_TRACE_PREFIX = "rf2.topic"
    }

    private fun HttpUrl.isLoginUrl(): Boolean =
        encodedPath.endsWith("/login.php") || encodedPath.endsWith("/login_validation.php")

    private fun String.looksLikeLoginPage(): Boolean {
        val lower = lowercase()
        return "login_validation.php" in lower &&
            "name=\"pseudo\"" in lower &&
            "name=\"password\"" in lower
    }
}
