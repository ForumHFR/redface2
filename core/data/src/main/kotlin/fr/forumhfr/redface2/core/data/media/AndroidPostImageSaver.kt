package fr.forumhfr.redface2.core.data.media

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.media.ImageSaveException
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import fr.forumhfr.redface2.core.domain.media.SavedPostImage
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import okio.buffer

/**
 * Android [PostImageSaver] (#831): writes a post image into the shared `Pictures/Redface2`
 * collection via `MediaStore.Images` — `minSdk = 29`, so scoped storage applies and NO runtime
 * permission is needed for app-created rows.
 *
 * Byte source (arbitrated contract — « octets ORIGINAUX, jamais réencoder ») :
 *  1. the Coil singleton [coil3.ImageLoader]'s disk cache — Coil 3 stores the RAW network body
 *     (`DiskCache.openSnapshot(url).data`, key = URL), so an animated GIF is saved animated and a
 *     JPEG keeps its original compression. The loader is resolved lazily via
 *     [SingletonImageLoader.get], same no-`:app`-dependency seam as `DefaultImageCacheMaintenance`;
 *  2. fallback: a network re-fetch on the [AnonymousClient] OkHttp client (the same client the
 *     image pipeline uses — no HFR auth cookies leaked to external image hosts). This is the
 *     expected path for MP media since #1096: their render and probe requests deliberately never
 *     populate Coil's disk cache, but « Enregistrer l'image » remains available at one extra fetch.
 *
 * The decoded bitmap is NEVER re-encoded. Write protocol: the row is inserted `IS_PENDING = 1`
 * (invisible to galleries), the bytes streamed, then the row flipped to `IS_PENDING = 0`; a write
 * failure deletes the orphan row so no ghost entry survives. All I/O hops to [ioDispatcher]
 * (project rule: data sources own their dispatcher), and a hard [MAX_SAVE_BYTES] ceiling bounds
 * both byte sources against a pathological input, mirroring `AndroidImageUploadReader`.
 */
@Singleton
internal class AndroidPostImageSaver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:AnonymousClient private val httpClient: OkHttpClient,
) : PostImageSaver {

    override suspend fun save(url: String): SavedPostImage = withContext(ioDispatcher) {
        val bytes = readFromCoilDiskCache(url) ?: fetchFromNetwork(url)
        val mediaType = resolveImageMediaType(bytes, url)
        val displayName = imageDisplayName(url, mediaType)
        insertIntoMediaStore(bytes, mediaType.mimeType, displayName)
        SavedPostImage(displayName = displayName)
    }

    /**
     * Original bytes from Coil's disk cache (key = the request URL, Coil 3 default). Null on a
     * cache miss, an absent disk cache, or an unreadable snapshot (→ the network fallback takes
     * over) ; only [ImageSaveException.TooLarge] escapes — re-fetching would hit the same ceiling.
     */
    private fun readFromCoilDiskCache(url: String): ByteArray? {
        val diskCache = SingletonImageLoader.get(context).diskCache
        val snapshot = diskCache?.openSnapshot(url)
        if (diskCache == null || snapshot == null) return null
        return try {
            diskCache.fileSystem.source(snapshot.data).buffer().use { drainBounded(it) }
        } catch (ignored: IOException) {
            null
        } finally {
            snapshot.close()
        }
    }

    /** Network re-fetch of the original bytes on the anonymous client (cache miss path). */
    private fun fetchFromNetwork(url: String): ByteArray = try {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ImageSaveException.Fetch()
            drainBounded(response.body.source())
        }
    } catch (e: IOException) {
        throw ImageSaveException.Fetch(e)
    } catch (e: IllegalArgumentException) {
        // Request.Builder.url refuses non-HTTP(S) URLs — the UI eligibility gate should have
        // filtered these, but a typed failure beats a crash if one slips through.
        throw ImageSaveException.Fetch(e)
    }

    /** Copies [source] into a byte array, throwing [ImageSaveException.TooLarge] past the ceiling. */
    private fun drainBounded(source: BufferedSource): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, DEFAULT_BUFFER_SIZE.toLong())
            if (read < 0L) break
            total += read
            if (total > MAX_SAVE_BYTES) throw ImageSaveException.TooLarge(MAX_SAVE_BYTES)
        }
        return buffer.readByteArray()
    }

    /** MediaStore write with the IS_PENDING 1 → 0 protocol and orphan-row cleanup on failure. */
    @Suppress("ThrowsCount") // The IS_PENDING protocol's failure exits ARE the contract.
    private fun insertIntoMediaStore(bytes: ByteArray, mimeType: String, displayName: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pendingValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collection, pendingValues) ?: throw ImageSaveException.Storage()
        try {
            val stream = resolver.openOutputStream(itemUri)
                ?: throw IOException("ContentResolver returned no output stream for $itemUri")
            stream.use { it.write(bytes) }
            val publishedValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(itemUri, publishedValues, null, null)
        } catch (e: IOException) {
            // Never leave a ghost IS_PENDING row behind a failed write.
            resolver.delete(itemUri, null, null)
            throw ImageSaveException.Storage(e)
        }
    }

    private companion object {
        /** Gallery destination, surfaced to the user in the success feedback string. */
        private const val RELATIVE_PATH = "Pictures/Redface2"

        /**
         * Hard anti-OOM ceiling (32 MiB) — far above any real forum image, far below the heap.
         * Same rationale and value as `AndroidImageUploadReader.MAX_READ_BYTES`.
         */
        private const val MAX_SAVE_BYTES = 32L * 1024 * 1024
    }
}
