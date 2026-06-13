package fr.forumhfr.redface2.core.data.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import java.io.IOException
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
 * itself uses — so the editor renders one coherent error path. The size cap is NOT enforced here :
 * the provider owns the per-host limit and throws [UploadException.TooLarge], keeping a single source
 * of truth for what each host accepts.
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
        val bytes = runCatching {
            resolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw IOException("ContentResolver returned no stream for $parsed")
        }.getOrElse { error -> throw UploadException.Network(error) }
        ImageUpload(bytes = bytes, mimeType = mimeType, displayName = displayName)
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
    }
}
