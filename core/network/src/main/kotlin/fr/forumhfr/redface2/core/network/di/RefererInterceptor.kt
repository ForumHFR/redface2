package fr.forumhfr.redface2.core.network.di

import okhttp3.Interceptor
import okhttp3.Response

/** Adds the host-required HFR provenance header without disclosing it to unrelated image hosts. */
internal class RefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (REFERER_HOST_ALLOWLIST.none { request.url.host == it || request.url.host.endsWith(".$it") }) {
            return chain.proceed(request)
        }

        return chain.proceed(
            request.newBuilder()
                .header("Referer", HFR_REFERER)
                .build(),
        )
    }

    private companion object {
        const val HFR_REFERER = "https://forum.hardware.fr/"
        val REFERER_HOST_ALLOWLIST = setOf("reho.st")
    }
}
