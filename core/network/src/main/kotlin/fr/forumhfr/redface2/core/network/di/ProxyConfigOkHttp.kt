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
            val credential = Credentials.basic(normalized.username.orEmpty(), normalized.password.orEmpty())
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

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

private fun String.isHardwareFrHost(): Boolean =
    equals("hardware.fr", ignoreCase = true) ||
        endsWith(".hardware.fr", ignoreCase = true)

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
