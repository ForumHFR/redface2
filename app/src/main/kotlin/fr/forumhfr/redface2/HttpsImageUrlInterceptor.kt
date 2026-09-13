package fr.forumhfr.redface2

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Upgrades only image hosts whose HTTPS endpoint is known to serve the same resource. */
internal class HttpsImageUrlInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = (request.data as? String)?.toHttpUrlOrNull()
        val secureRequest = if (url?.scheme == "http" && url.host in HTTPS_IMAGE_HOST_ALLOWLIST) {
            request.newBuilder()
                .data(url.newBuilder().scheme("https").build().toString())
                .build()
        } else {
            null
        }

        return secureRequest?.let(chain::withRequest)?.proceed() ?: chain.proceed()
    }

    private companion object {
        val HTTPS_IMAGE_HOST_ALLOWLIST = setOf("reho.st")
    }
}
