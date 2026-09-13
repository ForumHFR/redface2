package fr.forumhfr.redface2.core.network.di

import okhttp3.Interceptor
import okhttp3.Response

/** Adds the host-required HFR provenance header without disclosing it to unrelated image hosts. */
internal class RefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isRehostHost(request.url.host)) {
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
    }
}

/** Exact reho.st host or one of its strict subdomains; shared by upgrade and Referer policies. */
internal fun isRehostHost(host: String): Boolean = host == REHOST_HOST || host.endsWith(".$REHOST_HOST")

private const val REHOST_HOST = "reho.st"
