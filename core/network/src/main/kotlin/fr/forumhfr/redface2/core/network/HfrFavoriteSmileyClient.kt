package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import fr.forumhfr.redface2.core.network.qualifiers.MutationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** #1154 — sensitive account favorites traffic, isolated from generic HTML calls and diagnostics. */
@Singleton
class HfrFavoriteSmileyClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:MutationClient private val mutation: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** #1154 — the single working page carries the complete ordered list and both account tokens. */
    suspend fun getFavoriteSmileyForm(): String {
        val url = baseUrl.newBuilder().addPathSegments("user/supprperso.php")
            .addQueryParameter("config", "hfr.inc").build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-store").get().build()
        return executeFavoriteSmileyHtml(favoriteSmileyReadClient, request)
    }

    suspend fun addFavoriteSmiley(hashCheck: String, token: String): String {
        val url = baseUrl.newBuilder().addPathSegments("user/addperso.php")
            .addQueryParameter("config", "hfr.inc").build()
        val body = FormBody.Builder(Charsets.UTF_8).add("hash_check", hashCheck).add("smilie", token).build()
        val request = Request.Builder().url(url).post(FavoriteSmileyRequestBody(body)).build()
        return executeFavoriteSmileyHtml(favoriteSmileyMutationClient, request)
    }

    /** All rows are mandatory: this endpoint rewrites the entire list, even on a partial POST. */
    suspend fun removeFavoriteSmileys(
        hashCheck: String,
        codehex: String,
        tokens: List<String>,
        deleteIndex: Int,
    ): String {
        require(codehex.matches(Regex("[0-9a-fA-F]+"))) { "Invalid favorite smiley credential" }
        require(deleteIndex in tokens.indices) { "Invalid favorite smiley deletion index" }
        val url = baseUrl.newBuilder().addPathSegments("user/supprperso_validation.php")
            .addQueryParameter("config", "hfr.inc").addQueryParameter("codehex", codehex).build()
        val body = FormBody.Builder(Charsets.UTF_8).add("hash_check", hashCheck).apply {
            tokens.forEachIndexed { index, token ->
                add("smiley$index", token)
                if (index == deleteIndex) add("delete$index", "on")
            }
        }.build()
        val request = Request.Builder().url(url).post(FavoriteSmileyRequestBody(body)).build()
        return executeFavoriteSmileyHtml(favoriteSmileyMutationClient, request)
    }

    // Only the repository emits operation + result for this sensitive path, no generic call details.
    private val favoriteSmileyReadClient: OkHttpClient by lazy {
        authenticated.newBuilder().cache(null).eventListener(EventListener.NONE)
            .followRedirects(false).followSslRedirects(false).build()
    }

    private val favoriteSmileyMutationClient: OkHttpClient by lazy {
        mutation.newBuilder().cache(null).eventListener(EventListener.NONE)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }

    /** Never expose URL, query, HTML, or the original cause (including body-read/transport failures). */
    @Suppress("SwallowedException", "ThrowsCount") // Keep every failure boundary free of account credentials.
    private suspend fun executeFavoriteSmileyHtml(client: OkHttpClient, request: Request): String = try {
        client.newCall(request).executeCancellable { response ->
            val loginRedirect = response.header("Location")?.let { response.request.url.resolve(it)?.isLoginUrl() }
            if (response.request.url.isLoginUrl() || (response.code in 300..399 && loginRedirect == true)) {
                throw SessionExpiredException("[redacted]")
            }
            if (!response.isSuccessful) throw IOException("HFR favorite smiley HTTP failure")
            val html = response.body.string()
            if (html.looksLikeLoginPage()) throw SessionExpiredException("[redacted]")
            html
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: SessionExpiredException) {
        throw SessionExpiredException("[redacted]")
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Do not retain the original cause: OkHttp failures can contain the credential-bearing URL.
        throw IOException("HFR favorite smiley request failed")
    }

    /** Keep cancellation wired to Call.cancel until the response body is consumed and closed. */
    private suspend fun <T> Call.executeCancellable(readResponse: (Response) -> T): T = withContext(ioDispatcher) {
        val call = this@executeCancellable
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            continuation.resumeWith(runCatching { call.execute().use(readResponse) })
        }
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
