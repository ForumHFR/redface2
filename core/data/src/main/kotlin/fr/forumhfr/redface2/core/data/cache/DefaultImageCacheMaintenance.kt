package fr.forumhfr.redface2.core.data.cache

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.cache.ImageCacheMaintenance
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Default implementation of [ImageCacheMaintenance] — clears the Coil singleton
 * [coil3.ImageLoader]'s memory and disk caches.
 *
 * The loader is resolved lazily at clear time via [SingletonImageLoader.get] on the
 * injected application context: in production that returns the loader built by
 * `RedfaceApplication` (which implements `SingletonImageLoader.Factory`), without
 * introducing a `:core:data → :app` dependency; in tests a fake loader is installed
 * up-front via `SingletonImageLoader.setUnsafe`.
 *
 * The whole clear runs in [withContext] on the IO dispatcher: the disk-cache clear
 * deletes files (project rule repos-must-wrap-io), and the memory-cache clear is cheap
 * enough that splitting dispatchers would only add complexity. Both caches are nullable
 * on the loader (a loader can be configured without either) — null simply means nothing
 * to clear. Same "no DiagnosticsLog" rationale as [DefaultTopicCacheMaintenance]: the
 * result is surfaced through `SettingsState` and there is no payload to redact.
 */
@Singleton
class DefaultImageCacheMaintenance @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImageCacheMaintenance {

    override suspend fun clearImageCache() {
        withContext(ioDispatcher) {
            val imageLoader = SingletonImageLoader.get(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }
    }
}
