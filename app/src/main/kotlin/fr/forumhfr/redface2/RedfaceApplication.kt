package fr.forumhfr.redface2

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import fr.forumhfr.redface2.core.data.cache.CacheInvalidator
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Coil 3 expects the singleton [ImageLoader] to be supplied via [SingletonImageLoader.Factory] on
 * the `Application` class — `AsyncImage` call sites scattered across `:core:ui` and the feature
 * modules read from that singleton. The factory wires a single [AnimatedImageDecoder.Factory] so
 * GIF smileys and `[img]` GIFs animate at the call site without each composable having to know
 * (cf. issue #109). Decoders are cheap and global; per-call-site override would defeat the point.
 *
 * `coil-network-okhttp` is registered with the anonymous OkHttp client so image loads share the
 * same user proxy / timeout / user-agent configuration as the rest of the HFR traffic.
 *
 * `minSdk = 29`, so [AnimatedImageDecoder] (API 28+) is always available — no GIF-decoder fallback
 * for older devices to maintain.
 */
@HiltAndroidApp
class RedfaceApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var cacheInvalidator: CacheInvalidator

    @Inject
    @AnonymousClient
    lateinit var imageClient: OkHttpClient

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                add(AnimatedImageDecoder.Factory())
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        // Wires the auth-state listener that wipes per-user caches on logout /
        // account switch (cf. [CacheInvalidator]). Started here so the listener
        // is alive for the full process lifetime — there is no point trying to
        // stop it before the process dies.
        cacheInvalidator.start()
    }
}
