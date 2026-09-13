package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.network.HfrConstants
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {
    @Test
    fun `mutation and upload disable retries while read clients preserve them`() {
        val baseClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertTrue(authenticated.retryOnConnectionFailure)
        assertTrue(anonymous.retryOnConnectionFailure)
        assertTrue(image.retryOnConnectionFailure)
        assertFalse(mutation.retryOnConnectionFailure)
        assertFalse(upload.retryOnConnectionFailure)
        assertSame(cookieJar, mutation.cookieJar)
    }

    @Test
    fun `only the image client installs image host interceptors in upgrade then referer order`() {
        val baseClient = OkHttpClient()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertEquals(
            listOf(RehostHttpsUpgradeInterceptor::class, RefererInterceptor::class),
            image.interceptors.map { it::class },
        )
        listOf(baseClient, authenticated, anonymous, mutation, upload).forEach { client ->
            assertFalse(client.interceptors.any {
                it is RehostHttpsUpgradeInterceptor || it is RefererInterceptor
            })
        }
    }

    @Test
    fun `four HFR clients refuse SSL redirects while the image client follows them`() {
        val baseClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        listOf(authenticated, mutation, upload, anonymous).forEach { client ->
            assertTrue(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
        assertTrue(image.followRedirects)
        assertTrue(image.followSslRedirects)
    }

    @Test
    fun `anonymous HFR and image clients never carry cookies`() {
        val baseClient = OkHttpClient()

        assertSame(CookieJar.NO_COOKIES, NetworkModule.provideAnonymousClient(baseClient).cookieJar)
        assertSame(CookieJar.NO_COOKIES, NetworkModule.provideImageClient(baseClient).cookieJar)
    }

    @Test
    fun `HFR base URL stays HTTPS`() {
        assertTrue(HfrConstants.BASE_URL.startsWith("https://"))
    }
}
