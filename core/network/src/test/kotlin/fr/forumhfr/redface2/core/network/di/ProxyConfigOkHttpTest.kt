package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigOkHttpTest {

    @Test
    fun `disabled proxy leaves the OkHttp default selector behaviour untouched`() {
        // Compare against a baseline client built from a fresh Builder: both must resolve
        // proxies identically for any URL. This pins the contract "applyProxyConfig(disabled)
        // installs no custom selector" without coupling to the JVM default's return value,
        // which depends on system properties (`http.proxyHost`, etc.) and would flake on
        // CI environments configured with a system proxy.
        val baseline = OkHttpClient.Builder().build()
        val client = OkHttpClient.Builder()
            .applyProxyConfig(ProxyConfig(enabled = false, host = "proxy.local", port = 8_080))
            .build()

        assertNull(client.proxy)
        assertEquals(
            baseline.proxySelector.select(URI("https://forum.hardware.fr/")),
            client.proxySelector.select(URI("https://forum.hardware.fr/")),
        )
        assertEquals(
            baseline.proxySelector.select(URI("https://rehost.diberie.com/")),
            client.proxySelector.select(URI("https://rehost.diberie.com/")),
        )
    }

    @Test
    fun `enabled proxy routes HFR forum host through the user proxy`() {
        val client = clientWithEnabledProxy()

        // `client.proxy` was never assigned: applyProxyConfig only calls Builder.proxySelector(...)
        // and never Builder.proxy(...). OkHttp keeps both fields independent.
        assertNull(client.proxy)
        assertHttpProxy(client.selectProxy("https://forum.hardware.fr/"))
    }

    @Test
    fun `enabled proxy routes HFR image asset host through the user proxy`() {
        val client = clientWithEnabledProxy()
        assertHttpProxy(client.selectProxy("https://forum-images.hardware.fr/icones/smilies/jap.gif"))
    }

    @Test
    fun `enabled proxy routes hardware fr root through the user proxy`() {
        val client = clientWithEnabledProxy()
        assertHttpProxy(client.selectProxy("https://hardware.fr/"))
    }

    @Test
    fun `enabled proxy keeps external image hosts direct`() {
        val client = clientWithEnabledProxy()
        // Real user-reported case: a proxy that only authorises HFR must not break external image hosts.
        assertEquals(Proxy.NO_PROXY, client.selectProxy("https://rehost.diberie.com/Picture/Get/r/511520"))
    }

    @Test
    fun `enabled proxy refuses neighbour hosts that only contain hardware fr`() {
        val client = clientWithEnabledProxy()
        // Anti-substring guard: a future regression to `contains("hardware.fr")` must fail this test.
        assertEquals(Proxy.NO_PROXY, client.selectProxy("https://forum.hardware.fr.evil.example/"))
        assertEquals(Proxy.NO_PROXY, client.selectProxy("https://not-hardware.fr/"))
    }

    @Test
    fun `enabled proxy matches HFR host case-insensitively`() {
        val client = clientWithEnabledProxy()
        assertHttpProxy(client.selectProxy("https://Forum.Hardware.Fr/"))
    }

    @Test
    fun `enabled proxy still routes HFR through user proxy when URI has a non-standard port`() {
        val client = clientWithEnabledProxy()
        // Defensive: matching is on the host alone, not host:port.
        assertHttpProxy(client.selectProxy("https://forum.hardware.fr:8443/"))
    }

    @Test
    fun `enabled proxy matches HFR host with trailing dot FQDN form`() {
        val client = clientWithEnabledProxy()
        // FQDN absolute form: some resolvers preserve the trailing dot. The selector must still match.
        assertHttpProxy(client.selectProxy("https://forum.hardware.fr./"))
        assertHttpProxy(client.selectProxy("https://hardware.fr./"))
    }

    @Test
    fun `proxy selector survives newBuilder copy used by authenticated and anonymous clients`() {
        // NetworkModule.provideAuthenticatedClient / provideAnonymousClient derive their clients
        // via `baseClient.newBuilder().cookieJar(...).build()`. OkHttp's Builder copy constructor
        // is supposed to preserve the ProxySelector — this test pins that contract so a future
        // OkHttp upgrade or refactor can't silently break HFR routing through the user proxy.
        val base = clientWithEnabledProxy()
        val derived = base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        assertHttpProxy(derived.selectProxy("https://forum.hardware.fr/"))
        assertEquals(Proxy.NO_PROXY, derived.selectProxy("https://rehost.diberie.com/"))
    }

    @Test
    fun `enabled proxy keeps URIs without a host direct`() {
        val client = clientWithEnabledProxy()
        // OkHttp will never call select() with these in practice, but the contract is host-only.
        assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(URI("mailto:test@example.com")))
        assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(URI("file:/tmp/image.gif")))
    }

    @Test
    fun `proxy credentials install Proxy-Authorization only once`() {
        val client = OkHttpClient.Builder()
            .applyProxyConfig(
                ProxyConfig(
                    enabled = true,
                    host = "proxy.local",
                    port = 8_080,
                    username = "user",
                    password = "secret",
                ),
            )
            .build()

        val request = Request.Builder()
            .url("https://forum.hardware.fr/")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Authentication Required")
            .build()

        val authenticated = client.proxyAuthenticator.authenticate(null, response)
        assertEquals(
            Credentials.basic("user", "secret", Charsets.UTF_8),
            authenticated?.header("Proxy-Authorization"),
        )

        val replayResponse = response.newBuilder()
            .request(requireNotNull(authenticated))
            .build()
        assertNull(client.proxyAuthenticator.authenticate(null, replayResponse))
    }

    @Test
    fun `proxy credentials encode non-latin1 password in UTF-8`() {
        // Regression test: Credentials.basic without an explicit charset defaults to ISO-8859-1,
        // which silently mis-encodes accents and yields a 407 loop on proxies that expect UTF-8.
        val client = OkHttpClient.Builder()
            .applyProxyConfig(
                ProxyConfig(
                    enabled = true,
                    host = "proxy.local",
                    port = 8_080,
                    username = "user",
                    password = "été€",
                ),
            )
            .build()

        val request = Request.Builder().url("https://forum.hardware.fr/").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Authentication Required")
            .build()
        val authenticated = client.proxyAuthenticator.authenticate(null, response)

        assertEquals(
            Credentials.basic("user", "été€", Charsets.UTF_8),
            authenticated?.header("Proxy-Authorization"),
        )
    }

    private fun clientWithEnabledProxy(): OkHttpClient = OkHttpClient.Builder()
        .applyProxyConfig(ProxyConfig(enabled = true, host = "proxy.local", port = 8_080))
        .build()

    private fun OkHttpClient.selectProxy(url: String): Proxy =
        proxySelector.select(URI(url)).single()

    private fun assertHttpProxy(proxy: Proxy) {
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.local", address.hostString)
        assertEquals(8_080, address.port)
    }
}
