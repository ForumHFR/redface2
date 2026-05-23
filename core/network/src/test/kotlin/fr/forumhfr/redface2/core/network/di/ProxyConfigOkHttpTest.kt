package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy
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

        assertNull(client.proxy)
    }

    @Test
    fun `usable proxy config installs an HTTP proxy`() {
        val client = OkHttpClient.Builder()
            .applyProxyConfig(ProxyConfig(enabled = true, host = "proxy.local", port = 8_080))
            .build()

        val proxy = requireNotNull(client.proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.local", address.hostString)
        assertEquals(8_080, address.port)
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
}
