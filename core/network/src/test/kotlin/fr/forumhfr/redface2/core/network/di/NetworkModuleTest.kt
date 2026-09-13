package fr.forumhfr.redface2.core.network.di

import okhttp3.CookieJar
import okhttp3.OkHttpClient
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
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertTrue(authenticated.retryOnConnectionFailure)
        assertTrue(anonymous.retryOnConnectionFailure)
        assertFalse(mutation.retryOnConnectionFailure)
        assertFalse(upload.retryOnConnectionFailure)
        assertSame(cookieJar, mutation.cookieJar)
    }

    @Test
    fun `only the anonymous client installs the image host referer interceptor`() {
        val baseClient = OkHttpClient()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertTrue(anonymous.interceptors.any { it is RefererInterceptor })
        assertFalse(baseClient.interceptors.any { it is RefererInterceptor })
        assertFalse(authenticated.interceptors.any { it is RefererInterceptor })
        assertFalse(mutation.interceptors.any { it is RefererInterceptor })
        assertFalse(upload.interceptors.any { it is RefererInterceptor })
    }
}
