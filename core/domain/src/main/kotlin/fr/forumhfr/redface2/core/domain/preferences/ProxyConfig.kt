package fr.forumhfr.redface2.core.domain.preferences

/**
 * User-managed proxy for all HFR traffic.
 *
 * The MVP intentionally supports HTTP-style proxies only. Java/OkHttp's
 * [java.net.Proxy.Type.HTTP] is also the right transport for HTTPS requests:
 * OkHttp sends CONNECT through the proxy for TLS targets.
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val scheme: ProxyScheme = ProxyScheme.HTTP,
    val host: String = "",
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in MIN_PORT..MAX_PORT

    val hasCredentials: Boolean
        get() = !username.isNullOrBlank() && password != null

    fun normalized(): ProxyConfig = copy(
        host = host.trim(),
        username = username?.trim()?.ifBlank { null },
        password = password?.ifBlank { null },
    )

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
    }
}

enum class ProxyScheme {
    HTTP,
    HTTPS,
}
