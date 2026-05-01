package fr.forumhfr.redface2.core.network

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

        val client = if (useAuth) authenticated else anonymous
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HFR returned ${response.code} for $url")
            }
            response.body.string()
        }
    }

    /**
     * Fetches the authenticated drapeaux page filtered by [owntopic] :
     *
     * - `owntopic=1` → drapeaux cyan (sujets participés)
     * - `owntopic=2` → drapeaux rouges (lecture suivie uniquement)
     * - `owntopic=3` → étoiles jaunes (favoris)
     *
     * Always uses the authenticated client — HFR redirects this endpoint to /login.php
     * for an anonymous request. Mirrors the legacy v1 `META_PAGE_URL` query string so any
     * server-side filter HFR cares about stays in place.
     */
    suspend fun getFlagsPage(owntopic: Int): String {
        require(owntopic in 1..3) { "owntopic must be in 1..3, got $owntopic" }
        val url = baseUrl.newBuilder()
            .addPathSegment("forum1f.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("owntopic", owntopic.toString())
            .addQueryParameter("new", "0")
            .addQueryParameter("nojs", "0")
            .build()

        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
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

    private fun Call.executeAuthenticatedHtml(): String = execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HFR returned ${response.code} for ${response.request.url}")
        }
        val html = response.body.string()
        val finalUrl = response.request.url
        if (finalUrl.isLoginUrl() || html.looksLikeLoginPage()) {
            throw SessionExpiredException(finalUrl.toString())
        }
        html
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
