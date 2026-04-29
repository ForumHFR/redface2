package fr.forumhfr.redface2.core.network.auth

import android.util.Log
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
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
    private val diagnostics: DiagnosticsLog,
) {

    /** Tee an event to both logcat AND the in-app diagnostics buffer. */
    private fun logI(message: String) {
        Log.i(LOG_TAG, message)
        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, message)
    }
    private fun logD(message: String) {
        Log.d(LOG_TAG, message)
        diagnostics.record(DiagnosticsLog.Level.DEBUG, LOG_TAG, message)
    }
    private fun logW(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(LOG_TAG, message, throwable) else Log.w(LOG_TAG, message)
        val full = if (throwable != null) {
            "$message — ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            message
        }
        diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, full)
    }

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

        // Alpha-friendly logcat + in-app trail. Pseudo is what the user typed and is
        // already surfaced everywhere (cookie, footer) — fine to log. Password is
        // NEVER logged.
        val codePoints = pseudo.codePointCount(0, pseudo.length)
        logI("login attempt: pseudo='$pseudo' len=${pseudo.length} codepoints=$codePoints")

        val (status, cookies, html) = try {
            client.newCall(request).execute().use { response ->
                val parsed = response.headers("Set-Cookie").mapNotNull { Cookie.parse(url, it) }
                Triple(response.code, parsed, response.body.string())
            }
        } catch (e: IOException) {
            logW("login network failure for pseudo='$pseudo'", e)
            return Result.failure(LoginError.Network(e))
        }

        // Cookie names only — values may contain session secrets. We tag md_user
        // separately because its presence/absence drives the classification.
        val cookieNames = cookies.joinToString(",") { it.name }
        val mdUserCookie = cookies.firstOrNull { it.name == COOKIE_MD_USER }
        val mdUserPresence = if (mdUserCookie == null) "absent" else "present(len=${mdUserCookie.value.length})"
        logD(
            "login response: http=$status htmlLen=${html.length} cookies=[$cookieNames] md_user=$mdUserPresence",
        )

        return classify(cookies, html, pseudo).also { result ->
            result.onFailure { error ->
                val detail = (error as? LoginError.Unknown)?.detail ?: error::class.simpleName
                logW("login classified as failure for pseudo='$pseudo': $detail")
            }
            result.onSuccess { logI("login classified as success for pseudo='$pseudo'") }
        }
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
        else -> {
            // Build a diagnostic that compares submitted pseudo vs cookie value WITHOUT
            // leaking the cookie verbatim — just enough for the alpha user to see if HFR
            // normalized casing, trimmed whitespace, or returned a different account.
            val cookieValue = cookies.first { it.name == COOKIE_MD_USER }.value
            val sameLength = cookieValue.length == pseudo.length
            val sameCaseInsensitive = cookieValue.equals(pseudo, ignoreCase = true)
            Result.failure(
                LoginError.Unknown(
                    "$COOKIE_MD_USER cookie does not match requested pseudo " +
                        "(submitted len=${pseudo.length} vs cookie len=${cookieValue.length}, " +
                        "sameLength=$sameLength, caseInsensitiveMatch=$sameCaseInsensitive)",
                ),
            )
        }
    }

    private companion object {
        const val LOG_TAG = "AuthRemoteDataSource"
        const val LOGIN_PATH = "login_validation.php"
        const val COOKIE_MD_USER = "md_user"
        const val INVALID_CREDS_MARKER = "Votre mot de passe ou nom d'utilisateur n'est pas valide"
        const val RATE_LIMIT_MARKER = "Afin de prévenir les tentatives de flood"
    }
}
