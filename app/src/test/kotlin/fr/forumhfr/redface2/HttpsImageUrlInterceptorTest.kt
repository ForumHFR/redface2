package fr.forumhfr.redface2

import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HttpsImageUrlInterceptorTest {

    @Test
    fun `http reho image is forwarded over https`() = runTest {
        val forwardedRequest = intercept("http://reho.st/x.png")

        assertEquals("https://reho.st/x.png", forwardedRequest.data)
    }

    @Test
    fun `http image on an unlisted host is forwarded unchanged`() = runTest {
        val request = imageRequest("http://example.org/x.png")
        val chain = mockk<Interceptor.Chain>()
        val result = mockk<ImageResult>()
        every { chain.request } returns request
        coEvery { chain.proceed() } returns result

        assertSame(result, HttpsImageUrlInterceptor().intercept(chain))
        coVerify(exactly = 1) { chain.proceed() }
        verify(exactly = 0) { chain.withRequest(any()) }
    }

    private suspend fun intercept(url: String): ImageRequest {
        val request = imageRequest(url)
        val chain = mockk<Interceptor.Chain>()
        val forwardedChain = mockk<Interceptor.Chain>()
        val forwardedRequest = slot<ImageRequest>()
        val result = mockk<ImageResult>()
        every { chain.request } returns request
        every { chain.withRequest(capture(forwardedRequest)) } returns forwardedChain
        coEvery { forwardedChain.proceed() } returns result

        assertSame(result, HttpsImageUrlInterceptor().intercept(chain))

        return forwardedRequest.captured
    }

    private fun imageRequest(url: String): ImageRequest =
        ImageRequest.Builder(RuntimeEnvironment.getApplication())
            .data(url)
            .build()
}
