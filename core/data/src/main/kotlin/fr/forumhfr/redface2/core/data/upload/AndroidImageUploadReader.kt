package fr.forumhfr.redface2.core.data.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Android [ImageUploadReader] (#459 PR2): resolves a photo-picker `Uri` into the platform-free
 * [ImageUpload] via [android.content.ContentResolver]. Lives in `:core:data` (an Android library
 * that already injects `@ApplicationContext`) so the editor ViewModel never touches `Uri` /
 * `ContentResolver` and stays JVM-unit-testable with a fake reader.
 *
 * All I/O hops to [ioDispatcher] (project rule: data sources own their dispatcher). A failure to
 * open / read the stream is mapped to [UploadException.Network] — the same typed surface the upload
 * itself uses — so the editor renders one coherent error path. A hard safety ceiling
 * ([MAX_READ_BYTES]) bounds the read to avoid an OOM on a pathological input BEFORE the provider can
 * act ; the per-host accepted size stays the provider's responsibility (it throws
 * [UploadException.TooLarge] for its own, smaller limit).
 */
@Singleton
internal class AndroidImageUploadReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImageUploadReader {

    override suspend fun read(uri: String): ImageUpload = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(parsed) ?: DEFAULT_MIME_TYPE
        val displayName = resolveDisplayName(parsed)
        val bytes = readBounded(resolver, parsed)
        ImageUpload(bytes = bytes, mimeType = mimeType, displayName = displayName)
    }

    /**
     * Reads the stream in chunks, rejecting anything past [MAX_READ_BYTES] as [UploadException.TooLarge]
     * BEFORE the whole payload is materialised — so a pathological input fails typed instead of OOM-ing.
     * Open/read failures map to [UploadException.Network] (same surface as the upload itself).
     */
    private fun readBounded(resolver: ContentResolver, uri: Uri): ByteArray = try {
        resolver.openInputStream(uri)?.use { drainBounded(it) }
            ?: throw IOException("ContentResolver returned no stream for $uri")
    } catch (e: UploadException) {
        throw e
    } catch (e: IOException) {
        throw UploadException.Network(e)
    }

    /** Copies [stream] into a byte array, throwing [UploadException.TooLarge] past [MAX_READ_BYTES]. */
    private fun drainBounded(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_READ_BYTES) throw UploadException.TooLarge(MAX_READ_BYTES)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Best-effort original file name from the picker's `OpenableColumns.DISPLAY_NAME`. Returns null
     * when the provider does not expose it (the upload providers then fall back to a constant
     * filename) ; a query failure is swallowed rather than failing the whole read — the name is
     * cosmetic, the bytes are what matter.
     */
    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    private companion object {
        /** Generic image MIME when the resolver cannot type the content (providers tolerate it). */
        private const val DEFAULT_MIME_TYPE = "image/*"

        /**
         * Hard anti-OOM ceiling (32 MiB) — well above any real photo-picker image, far below the
         * heap. NOT a per-host policy : the upload providers reject their own (smaller) limits.
         */
        private const val MAX_READ_BYTES = 32L * 1024 * 1024
    }
}
