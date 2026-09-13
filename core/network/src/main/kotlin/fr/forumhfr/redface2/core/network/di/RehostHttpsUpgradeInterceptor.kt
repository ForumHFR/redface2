package fr.forumhfr.redface2.core.network.di

import okhttp3.Interceptor
import okhttp3.Response

/** Upgrades legacy reho.st image URLs at the shared image-client boundary. */
internal class RehostHttpsUpgradeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val forwardedRequest = if (url.scheme == "http" && isRehostHost(url.host)) {
            request.newBuilder()
                .url(url.newBuilder().scheme("https").build())
                .build()
        } else {
            request
        }
        return chain.proceed(forwardedRequest)
    }
}
