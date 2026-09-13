package fr.forumhfr.redface2.core.data.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticRedactor
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
 * [UploadException.TooLarge] for its own, smaller limit). #988 adds a bounded, privacy-safe trail
 * to [DiagnosticsLog] without changing the resolved [ImageUpload].
 */
@Singleton
internal class AndroidImageUploadReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val diagnostics: DiagnosticsLog,
) : ImageUploadReader {

    override suspend fun read(uri: String): ImageUpload = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val startedAtNanos = System.nanoTime()
        var rawMimeType: String? = null
        var mimeType = DEFAULT_MIME_TYPE
        var metadata = ResolvedMetadata.unavailable("not_queried")
        var bytesRead = 0L
        try {
            rawMimeType = resolver.getType(parsed)
            mimeType = rawMimeType ?: DEFAULT_MIME_TYPE
            metadata = resolveDisplayName(resolver, parsed)
            val bytes = readBounded(resolver, parsed) { total -> bytesRead = total }
            ImageUpload(bytes = bytes, mimeType = mimeType, displayName = metadata.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadException) {
            recordReadFailure(e)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            recordReadFailure(e)
            throw UploadException.Network(e)
        } finally {
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                readSummary(
                    ReadTrace(
                        uri = parsed,
                        mime = ResolvedMime(raw = rawMimeType, effective = mimeType),
                        metadata = metadata,
                        bytesRead = bytesRead,
                        durationMs = elapsedMillis(startedAtNanos),
                    ),
                ),
            )
        }
    }

    /**
     * Reads the stream in chunks, rejecting anything past [MAX_READ_BYTES] as [UploadException.TooLarge]
     * BEFORE the whole payload is materialised — so a pathological input fails typed instead of OOM-ing.
     * Open/read failures map to [UploadException.Network] (same surface as the upload itself).
     */
    private fun readBounded(
        resolver: ContentResolver,
        uri: Uri,
        onBytesRead: (Long) -> Unit,
    ): ByteArray = resolver.openInputStream(uri)?.use { drainBounded(it, onBytesRead) }
        ?: throw IOException("ContentResolver returned no stream")

    /** Copies [stream] into a byte array, throwing [UploadException.TooLarge] past [MAX_READ_BYTES]. */
    private fun drainBounded(stream: InputStream, onBytesRead: (Long) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            onBytesRead(total)
            if (total > MAX_READ_BYTES) throw UploadException.TooLarge(MAX_READ_BYTES)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Best-effort metadata from the picker's `DISPLAY_NAME` and `SIZE` columns. A query failure does
     * not fail the byte read: it is reduced to a safe diagnostic carrying only the exception class.
     */
    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): ResolvedMetadata = try {
        resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return ResolvedMetadata.unavailable("none")
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val displayName = if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val declaredSize = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                }
                ResolvedMetadata(
                    displayName = displayName,
                    declaredSize = declaredSize,
                    displayNameDiagnostic = if (displayName == null) {
                        "display_name=unavailable (none)"
                    } else {
                        null
                    },
                )
            }
            ?: ResolvedMetadata.unavailable("none")
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        ResolvedMetadata.unavailable(e::class.qualifiedName ?: e.javaClass.name)
    }

    private fun readSummary(trace: ReadTrace): String = buildString {
        append("source=")
        append(safeSource(trace.uri))
        append(" mime_raw=")
        append(trace.mime.raw ?: "null")
        append(" fallback=")
        append(trace.mime.raw == null)
        append(" mime=")
        append(trace.mime.effective)
        append(" extension=")
        append(extensionOf(trace.metadata.displayName))
        append(" size_col=")
        append(trace.metadata.declaredSize ?: "absent")
        append(" bytes=")
        append(trace.bytesRead)
        append(" duration_ms=")
        append(trace.durationMs)
        trace.metadata.displayNameDiagnostic?.let {
            append(' ')
            append(it)
        }
    }

    private fun recordReadFailure(error: Exception) {
        val prefix = if (error is SecurityException || error.cause is SecurityException) "permission " else ""
        val exceptionClass = error::class.qualifiedName ?: error.javaClass.name
        val message = DiagnosticRedactor.redact(error.message ?: "none", MAX_LOGGED_ERROR_MESSAGE)
        val causeClass = error.cause?.let { cause ->
            cause::class.qualifiedName ?: cause.javaClass.name
        } ?: "none"
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG,
            "${prefix}ex=$exceptionClass: $message cause=$causeClass",
        )
    }

    private fun safeSource(uri: Uri): String = "${uri.scheme ?: "none"}://${uri.authority ?: "none"}"

    private fun extensionOf(displayName: String?): String = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { extension ->
            extension.length in 1..MAX_EXTENSION_LENGTH && extension.all(Char::isLetterOrDigit)
        }
        ?.lowercase()
        ?: "none"

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private data class ReadTrace(
        val uri: Uri,
        val mime: ResolvedMime,
        val metadata: ResolvedMetadata,
        val bytesRead: Long,
        val durationMs: Long,
    )

    private data class ResolvedMime(
        val raw: String?,
        val effective: String,
    )

    private data class ResolvedMetadata(
        val displayName: String?,
        val declaredSize: Long?,
        val displayNameDiagnostic: String?,
    ) {
        companion object {
            fun unavailable(reason: String) = ResolvedMetadata(
                displayName = null,
                declaredSize = null,
                displayNameDiagnostic = "display_name=unavailable ($reason)",
            )
        }
    }

    private companion object {
        /** Generic image MIME when the resolver cannot type the content (providers tolerate it). */
        private const val DEFAULT_MIME_TYPE = "image/*"
        private const val LOG_TAG = "UploadReader"
        private const val MAX_EXTENSION_LENGTH = 16
        private const val MAX_LOGGED_ERROR_MESSAGE = 160
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * Hard anti-OOM ceiling (32 MiB) — well above any real photo-picker image, far below the
         * heap. NOT a per-host policy : the upload providers reject their own (smaller) limits.
         */
        private const val MAX_READ_BYTES = 32L * 1024 * 1024
    }
}
