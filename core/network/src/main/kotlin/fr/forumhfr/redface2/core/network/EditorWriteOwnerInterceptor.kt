package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import java.net.URLDecoder
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Runs after OkHttp has selected the Cookie header and before CallServerInterceptor writes it.
 * Checking this immutable request closes the account-switch race without locking the cookie jar.
 * Untagged requests belong to other mutation flows, outside the editor's submission contract.
 */
internal class EditorWriteOwnerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        request.tag(EditorWriteOwner::class.java)?.let { expected ->
            val identities = request.headers.values("Cookie").flatMap { header ->
                header.split(';').map(String::trim).filter { it.substringBefore('=') == "md_user" }
                    .map { it.substringAfter('=', "") }
            }
            val actual = editorCookieOwner(identities)
            if (expected.owner == null || actual != expected.owner) {
                expected.rejectBeforeEmission()
            }
            // DNS, connect and TLS precede this network interceptor. From this point onwards
            // headers or body may reach HFR, even if CallServerInterceptor subsequently fails.
            if (request.method == "POST") expected.markEmissionPossible()
        }
        return chain.proceed(request)
    }
}

/** Same form-urlencoding and lowercase convention as the account/draft stores, for every cookie. */
internal fun editorCookieOwner(values: List<String>): String? {
    val identities = values.map { raw ->
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }
            .getOrDefault(raw.replace('+', ' ')).lowercase()
    }.distinct()
    return identities.singleOrNull()?.takeIf(String::isNotBlank)
}
