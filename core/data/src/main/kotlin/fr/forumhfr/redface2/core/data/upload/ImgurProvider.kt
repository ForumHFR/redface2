package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.network.qualifiers.UploadClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads to imgur (#459) via `POST https://api.imgur.com/3/image` with the user's own public
 * Client-ID (option B, never committed — pasted in Settings). Deletion is GUARANTEED through
 * `DELETE /3/image/{deletehash}`.
 *
 * The Client-ID is injected as a [Provider] so each call re-reads the current preference value: the
 * provider is a [Singleton] but the Client-ID can be set / changed after the graph is built. Imgur
 * uploads are only offered by the selector when a non-empty Client-ID is configured.
 *
 * All network work runs on [ioDispatcher] (project rule for OkHttp calls).
 *
 * @param baseUrl injected so tests can point the provider at a MockWebServer; defaults to the real
 * imgur API base in production via the DI binding.
 */
@Singleton
internal class ImgurProvider @Inject constructor(
    @param:UploadClient private val client: OkHttpClient,
    @param:UploadJson private val json: Json,
    @param:ImgurClientId private val clientId: Provider<String>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:Named(IMGUR_BASE_URL) private val baseUrl: String,
) : UploadProvider {

    override val id = UploadProviderId.IMGUR

    override suspend fun upload(image: ImageUpload): UploadedImage = withContext(ioDispatcher) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = image.displayName ?: DEFAULT_FILENAME,
                body = image.bytes.toRequestBody(image.mimeType.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/3/image")
            .header("Authorization", "Client-ID ${clientId.get()}")
            .post(body)
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw UploadException.Network(it) }
        response.use { resp ->
            if (!resp.isSuccessful) throw UploadException.Server(resp.code, id)
            val envelope = runCatching { json.decodeFromString<ImgurEnvelope>(resp.body.string()) }
                .getOrElse { throw UploadException.Malformed(id, it) }
            val data = envelope.data ?: throw UploadException.Malformed(id)
            UploadedImage(
                provider = id,
                imageUrl = data.link ?: throw UploadException.Malformed(id),
                thumbnailUrl = null,
                // imgur exposes size variants via URL suffixes, but the v1 contract does not derive
                // them — the editor's reduced mode falls back to the full link for imgur.
                resizedUrl = null,
                deleteHandle = data.deleteHash,
                expiresAt = null,
            )
        }
    }

    override suspend fun delete(deleteHandle: String): Boolean = withContext(ioDispatcher) {
        // Guaranteed deletion (#459): DELETE /3/image/{deletehash}.
        val request = Request.Builder()
            .url("$baseUrl/3/image/$deleteHandle")
            .header("Authorization", "Client-ID ${clientId.get()}")
            .delete()
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    internal companion object {
        /** Named binding key for the imgur API base URL (overridden in tests). */
        const val IMGUR_BASE_URL = "imgur_base_url"
        const val DEFAULT_BASE_URL = "https://api.imgur.com"
        private const val DEFAULT_FILENAME = "upload"
    }
}
