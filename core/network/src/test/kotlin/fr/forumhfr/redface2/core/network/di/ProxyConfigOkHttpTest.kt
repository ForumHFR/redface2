package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
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
    fun `disabled proxy leaves OkHttp builder without proxy`() {
        val client = OkHttpClient.Builder()
            .applyProxyConfig(ProxyConfig(enabled = false, host = "proxy.local", port = 8_080))
            .build()

        // Disabled: no custom proxy AND no custom selector that would forcibly route HFR through a user proxy.
        assertNull(client.proxy)
        // The selector must not route HFR through a custom proxy when the user proxy is disabled.
        // OkHttp's default selector returns NO_PROXY for ordinary HTTPS URLs in a unit-test JVM.
        assertEquals(
            listOf(Proxy.NO_PROXY),
            client.proxySelector.select(URI("https://forum.hardware.fr/")),
        )
    }

    @Test
    fun `enabled proxy routes HFR forum host through the user proxy`() {
        val client = clientWithEnabledProxy()

        // With a ProxySelector installed, OkHttp does not expose a singular `client.proxy`.
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
        assertEquals(Credentials.basic("user", "secret"), authenticated?.header("Proxy-Authorization"))

        val replayResponse = response.newBuilder()
            .request(requireNotNull(authenticated))
            .build()
        assertNull(client.proxyAuthenticator.authenticate(null, replayResponse))
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
