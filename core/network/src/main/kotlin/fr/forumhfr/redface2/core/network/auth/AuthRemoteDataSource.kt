package fr.forumhfr.redface2.core.network.auth

import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AuthRemoteDataSource @Inject constructor(
    @param:AuthenticatedClient private val client: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
) {

    /**
     * POSTs the login form to HFR. The CookieJar attached to the @AuthenticatedClient is what
     * actually persists the session — this method only classifies the response. Returns a
     * typed [LoginError] inside Result.failure on every non-success path so the caller can
     * branch without parsing messages.
     *
     * Detection logic (mirrors hfr-mcp/internal/hfr/client.go::Login, kept as the single
     * source of truth for HFR's idiosyncratic auth contract):
     * - Set-Cookie includes md_user with value == pseudo → Authenticated
     * - body contains "Votre mot de passe ou nom d'utilisateur n'est pas valide" → InvalidCredentials
     * - body contains "Afin de prévenir les tentatives de flood" → RateLimited
     * - anything else (cookie missing, unknown HTML) → Unknown
     */
    suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> {
        val url = baseUrl.newBuilder()
            .addPathSegment(LOGIN_PATH)
            .addQueryParameter("config", HfrConstants.CONFIG)
            .build()

        val body = FormBody.Builder()
            .add("pseudo", pseudo)
            .add("password", password)
            .build()

        val request = Request.Builder().url(url).post(body).build()

        val (cookies, html) = try {
            client.newCall(request).execute().use { response ->
                val parsed = response.headers("Set-Cookie").mapNotNull { Cookie.parse(url, it) }
                parsed to response.body.string()
            }
        } catch (e: IOException) {
            return Result.failure(LoginError.Network(e))
        }

        return classify(cookies, html, pseudo)
    }

    private fun classify(
        cookies: List<Cookie>,
        html: String,
        pseudo: String,
    ): Result<AuthState.Authenticated> = when {
        INVALID_CREDS_MARKER in html -> Result.failure(LoginError.InvalidCredentials)
        RATE_LIMIT_MARKER in html -> Result.failure(LoginError.RateLimited)
        cookies.any { it.name == COOKIE_MD_USER && it.value == pseudo } ->
            Result.success(AuthState.Authenticated(pseudo))
        cookies.none { it.name == COOKIE_MD_USER } ->
            Result.failure(LoginError.Unknown("expected $COOKIE_MD_USER cookie not set"))
        else -> Result.failure(LoginError.Unknown("$COOKIE_MD_USER cookie does not match requested pseudo"))
    }

    private companion object {
        const val LOGIN_PATH = "login_validation.php"
        const val COOKIE_MD_USER = "md_user"
        const val INVALID_CREDS_MARKER = "Votre mot de passe ou nom d'utilisateur n'est pas valide"
        const val RATE_LIMIT_MARKER = "Afin de prévenir les tentatives de flood"
    }
}
