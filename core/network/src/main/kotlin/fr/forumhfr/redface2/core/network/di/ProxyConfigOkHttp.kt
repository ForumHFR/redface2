package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * Installs the user proxy as a host-scoped [ProxySelector] : HFR hosts go
 * through the configured proxy, every other host stays direct. The MVP target
 * is "HFR via proxy utilisateur, reste du Web en direct" — a global
 * `.proxy(...)` would also route external `[img]` hosts (e.g. `rehost.diberie.com`)
 * through a proxy that may only authorise `*.hardware.fr`, breaking image
 * loads. Deliberately ignores `ProxySelector.getDefault()` for non-HFR hosts
 * so the behaviour stays deterministic across devices.
 */
internal fun OkHttpClient.Builder.applyProxyConfig(config: ProxyConfig): OkHttpClient.Builder = apply {
    val normalized = config.normalized()
    val port = normalized.port

    if (normalized.isUsable && port != null) {
        val hfrProxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(normalized.host, port),
        )
        proxySelector(HfrOnlyProxySelector(hfrProxy))

        if (normalized.hasCredentials) {
            // SECURITY: never record proxy username/password in DiagnosticsLog or request diagnostics.
            // UTF-8 charset: avoids silent 407 loops when the proxy password contains non-latin1
            // characters (accents, etc.). Modern proxies expect UTF-8; ISO-8859-1 (OkHttp legacy
            // default) would mis-encode the credentials.
            val credential = Credentials.basic(
                normalized.username.orEmpty(),
                normalized.password.orEmpty(),
                Charsets.UTF_8,
            )
            proxyAuthenticator { _, response ->
                if (response.request.header(PROXY_AUTHORIZATION) != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header(PROXY_AUTHORIZATION, credential)
                        .build()
                }
            }
        }
    }
}

/**
 * Routes only `hardware.fr` and its subdomains through [hfrProxy]. Every other
 * host is returned as [Proxy.NO_PROXY] so external image hosts (e.g.
 * `rehost.diberie.com`) keep loading in direct mode even when the user proxy
 * is enabled.
 *
 * Immutable; `select` is thread-safe. OkHttp may invoke it concurrently from
 * multiple I/O threads. Does not chain to `ProxySelector.getDefault()` for
 * non-HFR hosts so behaviour stays deterministic across Android device
 * proxy settings.
 */
private class HfrOnlyProxySelector(
    private val hfrProxy: Proxy,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        return if (host.isHardwareFrHost()) {
            listOf(hfrProxy)
        } else {
            listOf(Proxy.NO_PROXY)
        }
    }

    // Intentionally a no-op: OkHttp manages route failure via its own RouteDatabase,
    // and we don't surface proxy connect errors to DiagnosticsLog here to avoid leaking
    // the proxy host/credentials into any future log surface. Hook here later if we
    // ever add proxy-specific diagnostics (without leaking credentials).
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/**
 * Returns `true` when this host matches `hardware.fr` exactly or any subdomain
 * of `hardware.fr`. Trailing dot (`hardware.fr.`, FQDN absolute form) is
 * normalised away so a DNS-style absolute host still matches; this is
 * documented because `java.net.URI.getHost()` usually strips the trailing dot
 * but the resolver layer can occasionally hand one back.
 */
private fun String.isHardwareFrHost(): Boolean {
    val normalized = trimEnd('.')
    return normalized.equals(HFR_DOMAIN, ignoreCase = true) ||
        normalized.endsWith(".$HFR_DOMAIN", ignoreCase = true)
}

private const val HFR_DOMAIN = "hardware.fr"

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
