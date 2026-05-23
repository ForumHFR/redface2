package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Credentials
import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.applyProxyConfig(config: ProxyConfig): OkHttpClient.Builder = apply {
    val normalized = config.normalized()
    val port = normalized.port

    if (normalized.isUsable && port != null) {
        proxy(
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(normalized.host, port),
            ),
        )

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

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
