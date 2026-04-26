package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class HfrClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:AnonymousClient private val anonymous: OkHttpClient,
) {
    suspend fun getTopicPage(
        cat: Int,
        post: Int,
        page: Int,
        useAuth: Boolean = true,
    ): String {
        val url = HfrConstants.BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("forum2.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .addQueryParameter("page", page.toString())
            .build()

        val client = if (useAuth) authenticated else anonymous
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HFR returned ${response.code} for $url" }
            response.body.string()
        }
    }
}
