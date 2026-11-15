package fr.forumhfr.redface2.core.network.diagnostics

import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousProbeClientTest {
    @Test
    fun `dedicated client keeps proxy and TLS verification while removing account hooks and shared pool`() {
        val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .addInterceptor { error("Account interceptor must never run") }
            .addNetworkInterceptor { error("Account network interceptor must never run") }
            .authenticator { _, _ -> error("Account authentication must never run") }
            .proxyAuthenticator { _, _ -> error("Proxy authentication must never run") }
            .build()
        val probe = anonymousProbeClient(base)
        assertSame(base.proxy, probe.proxy)
        assertSame(base.sslSocketFactory, probe.sslSocketFactory)
        assertSame(base.hostnameVerifier, probe.hostnameVerifier)
        assertSame(base.certificatePinner, probe.certificatePinner)
        assertSame(CookieJar.NO_COOKIES, probe.cookieJar)
        assertSame(Authenticator.NONE, probe.authenticator)
        assertSame(Authenticator.NONE, probe.proxyAuthenticator)
        assertNull(probe.cache)
        assertEquals(listOf(Protocol.HTTP_1_1), probe.protocols)
        assertTrue(probe.interceptors.isEmpty())
        assertTrue(probe.networkInterceptors.isEmpty())
        assertTrue(probe.connectionPool !== base.connectionPool)
    }
}
