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
 * Deletion is BEST-EFFORT: the host never confirms removal (#459 NB — a 2xx can just swap the
 * picture for a placeholder), so [delete] fires the call, never throws, and ALWAYS returns `false`.
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
        val sentFilename = image.displayName ?: DEFAULT_FILENAME
        val partContentType = image.mimeType.toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = sentFilename,
                body = image.bytes.toRequestBody(partContentType),
            )
            .build()
        val request = Request.Builder().url(uploadUrl()).post(body).build()
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            UploadProviderDiagnostics.request(sentFilename, partContentType?.toString(), image.bytes.size),
        )
        val startedAtNanos = System.nanoTime()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw UploadException.Network(it) }
        response.use { resp ->
            val responseContentType = resp.body.contentType()?.toString()
            val raw = runCatching { resp.body.string() }
                .getOrElse { error ->
                    recordFailure(
                        code = resp.code,
                        contentType = responseContentType,
                        startedAtNanos = startedAtNanos,
                        result = "body_read_error",
                        failureBody = "unavailable",
                    )
                    throw error
                }
            if (!resp.isSuccessful) {
                recordFailure(
                    code = resp.code,
                    contentType = responseContentType,
                    startedAtNanos = startedAtNanos,
                    result = "http_error",
                    failureBody = raw,
                )
                throw UploadException.Server(resp.code, id)
            }
            val dto = runCatching { json.decodeFromString<DiberieResponse>(raw) }
                .getOrElse { error ->
                    recordFailure(
                        code = resp.code,
                        contentType = responseContentType,
                        startedAtNanos = startedAtNanos,
                        result = "unparseable",
                        failureBody = raw,
                    )
                    throw UploadException.Malformed(id, error)
                }
            val picId = dto.picId
            if (picId == null) {
                recordFailure(
                    code = resp.code,
                    contentType = responseContentType,
                    startedAtNanos = startedAtNanos,
                    result = "missing_picID",
                    failureBody = raw,
                )
                throw UploadException.Malformed(id)
            }
            recordSuccess(
                code = resp.code,
                contentType = responseContentType,
                startedAtNanos = startedAtNanos,
                picId = picId,
            )
            UploadedImage(
                provider = id,
                imageUrl = dto.picUrl ?: "$baseUrl/Picture/Get/f/$picId",
                thumbnailUrl = dto.thumbUrl ?: "$baseUrl/Picture/Get/t/$picId",
                // `.../Get/r/{id}` is diberie's ~300px reduced variant (the "vignette cliquable").
                resizedUrl = dto.resizedUrl ?: "$baseUrl/Picture/Get/r/$picId",
                deleteHandle = picId.toString(),
                // SelectedExpiryType=0 in the upload query → no advertised expiration.
                expiresAt = null,
            )
        }
    }

    override suspend fun delete(deleteHandle: String): Boolean = withContext(ioDispatcher) {
        // Best-effort cleanup (#459 NB). The host answers 2xx/redirect even when it merely swaps the
        // picture for a placeholder rather than removing it (live capture 2026-06-13), so a
        // successful HTTP exchange does NOT prove host-side deletion. We fire the request as a
        // courtesy, never throw, and ALWAYS report `false` (= not confirmed) — diberie declares
        // `UploadProviderId.confirmsHostDeletion = false`, and the «Mes images» screen only offers a
        // delete affordance for providers that DO confirm (imgur, which authenticates the delete).
        val form = FormBody.Builder().add("DeletePhoto_IdPhoto", deleteHandle).build()
        val request = Request.Builder().url("$baseUrl/Host/DeletePhoto").post(form).build()
        runCatching { client.newCall(request).execute().close() }
        false
    }

    private fun uploadUrl(): String = "$baseUrl/Host/UploadFiles?SelectedAlbumId=0&PrivateMode=false" +
        "&SendMail=false&KeepTags=&Comment=&SelectedExpiryType=0"

    private fun recordFailure(
        code: Int,
        contentType: String?,
        startedAtNanos: Long,
        result: String,
        failureBody: String,
    ) {
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG,
            UploadProviderDiagnostics.response(
                trace = UploadProviderDiagnostics.responseTrace(
                    code = code,
                    contentType = contentType,
                    startedAtNanos = startedAtNanos,
                    result = result,
                ),
                failureBody = failureBody,
            ),
        )
    }

    private fun recordSuccess(code: Int, contentType: String?, startedAtNanos: Long, picId: Long) {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            UploadProviderDiagnostics.response(
                trace = UploadProviderDiagnostics.responseTrace(
                    code = code,
                    contentType = contentType,
                    startedAtNanos = startedAtNanos,
                    result = "ok",
                ),
                detail = "picID=$picId",
            ),
        )
    }

    internal companion object {
        /** Named binding key for the diberie base URL (overridden in tests). */
        const val DIBERIE_BASE_URL = "diberie_base_url"
        const val DEFAULT_BASE_URL = "https://rehost.diberie.com"
        private const val LOG_TAG = "Diberie"
        private const val DEFAULT_FILENAME = "upload"
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
