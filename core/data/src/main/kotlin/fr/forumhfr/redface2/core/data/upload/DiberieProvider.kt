package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.network.qualifiers.UploadClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads to the diberie rehost host (#459), the default provider (no auth, no Client-ID).
 *
 * Deletion is BEST-EFFORT: server-side authorisation is not confirmed (#459 NB), so [delete] tries
 * the call and never throws — it returns `false` when the host did not confirm.
 *
 * All network work runs on [ioDispatcher] (project rule: every repository / data source that calls
 * an OkHttp client wraps it in `withContext(ioDispatcher)`).
 *
 * @param baseUrl injected so tests can point the provider at a MockWebServer; defaults to the real
 * host in production via the DI binding.
 */
@Singleton
internal class DiberieProvider @Inject constructor(
    @param:UploadClient private val client: OkHttpClient,
    @param:UploadJson private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val diagnostics: DiagnosticsLog,
    @param:Named(DIBERIE_BASE_URL) private val baseUrl: String,
) : UploadProvider {

    override val id = UploadProviderId.DIBERIE

    override suspend fun upload(image: ImageUpload): UploadedImage = withContext(ioDispatcher) {
        if (image.bytes.size > MAX_BYTES) throw UploadException.TooLarge(MAX_BYTES)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = image.displayName ?: DEFAULT_FILENAME,
                body = image.bytes.toRequestBody(image.mimeType.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder().url(uploadUrl()).post(body).build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw UploadException.Network(it) }
        response.use { resp ->
            if (!resp.isSuccessful) {
                diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "diberie upload rejected: HTTP ${resp.code}")
                throw UploadException.Server(resp.code, id)
            }
            val raw = resp.body.string()
            val dto = runCatching { json.decodeFromString<DiberieResponse>(raw) }
                .getOrElse { error ->
                    diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, parseFailureMessage(resp.code, raw))
                    throw UploadException.Malformed(id, error)
                }
            val picId = dto.picId
            if (picId == null) {
                diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, missingPicIdMessage(raw))
                throw UploadException.Malformed(id)
            }
            diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "diberie upload ok: picID=$picId")
            UploadedImage(
                provider = id,
                imageUrl = dto.picUrl ?: "$baseUrl/Picture/Get/f/$picId",
                thumbnailUrl = dto.thumbUrl ?: "$baseUrl/Picture/Get/t/$picId",
                deleteHandle = picId.toString(),
                // SelectedExpiryType=0 in the upload query → no advertised expiration.
                expiresAt = null,
            )
        }
    }

    override suspend fun delete(deleteHandle: String): Boolean = withContext(ioDispatcher) {
        // Best-effort: authorisation NOT confirmed (#459 NB). Try, never throw, report false on doubt.
        val form = FormBody.Builder().add("DeletePhoto_IdPhoto", deleteHandle).build()
        val request = Request.Builder().url("$baseUrl/Host/DeletePhoto").post(form).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun uploadUrl(): String = "$baseUrl/Host/UploadFiles?SelectedAlbumId=0&PrivateMode=false" +
        "&SendMail=false&KeepTags=&Comment=&SelectedExpiryType=0"

    // Diagnostic trail (surfaced in the in-app viewer, #445) — the raw body is truncated so a huge
    // HTML error page never floods the ring buffer. These are pure builders; the call site records.
    private fun parseFailureMessage(code: Int, raw: String): String =
        "diberie upload: unparseable response (HTTP $code): ${raw.take(MAX_LOGGED_BODY)}"

    private fun missingPicIdMessage(raw: String): String =
        "diberie upload: response without picID: ${raw.take(MAX_LOGGED_BODY)}"

    internal companion object {
        /** Named binding key for the diberie base URL (overridden in tests). */
        const val DIBERIE_BASE_URL = "diberie_base_url"
        const val DEFAULT_BASE_URL = "https://rehost.diberie.com"
        private const val LOG_TAG = "DiberieProvider"
        private const val MAX_LOGGED_BODY = 300
        private const val DEFAULT_FILENAME = "upload"
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
