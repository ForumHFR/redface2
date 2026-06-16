package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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
import kotlinx.coroutines.flow.first
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
 * The Client-ID is read from [UserPreferencesRepository] on each call so the current preference
 * value is honoured: the provider is a [Singleton] but the Client-ID can be set / changed after the
 * graph is built. Imgur uploads are only offered by the selector when a non-empty Client-ID is
 * configured; [upload] still guards a blank value with [UploadException.Configuration] (#474). The
 * read is a suspending `observeImgurClientId().first()` collected inside `withContext(ioDispatcher)`
 * — no `runBlocking` bridge (#474).
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
    private val userPreferencesRepository: UserPreferencesRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:Named(IMGUR_BASE_URL) private val baseUrl: String,
) : UploadProvider {

    override val id = UploadProviderId.IMGUR

    override suspend fun upload(image: ImageUpload): UploadedImage = withContext(ioDispatcher) {
        // Fail fast on misconfiguration: a blank Client-ID would otherwise produce an opaque imgur
        // 400/403 only after a wasted round-trip (#474). Surface it as a typed config error instead.
        // Suspending read of the current preference — no runBlocking (#474).
        val resolvedClientId = userPreferencesRepository.observeImgurClientId().first()
        if (resolvedClientId.isBlank()) {
            throw UploadException.Configuration("imgur Client-ID is not configured")
        }
        // Reject an over-limit STILL image locally, before any POST: imgur would refuse it anyway
        // (#474). Animated GIFs are exempt — imgur allows them up to 200 MB, so the 20 MB still-image
        // cap must not block a large-but-valid GIF; the reader's MAX_READ_BYTES ceiling already bounds
        // every payload, well under imgur's GIF limit (#474, Codex review).
        if (image.mimeType != GIF_MIME && image.bytes.size > MAX_BYTES) {
            throw UploadException.TooLarge(MAX_BYTES)
        }
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
            .header("Authorization", "Client-ID $resolvedClientId")
            .post(body)
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw UploadException.Network(it) }
        response.use { resp ->
            // Best-effort body read: a truncated / timed-out body must NOT leak a raw IOException
            // past the UploadException contract (#474, Codex review). A null body is fine — a non-2xx
            // still reports its HTTP status below, and a 2xx with no parseable body falls to Malformed.
            val raw = runCatching { resp.body.string() }.getOrNull()
            val envelope = raw?.let { runCatching { json.decodeFromString<ImgurEnvelope>(it) }.getOrNull() }
            // A `success:false` envelope is an application-level refusal even on a 2xx transport:
            // map it to Server with the host's `data.error` when present (#474), instead of
            // stumbling into a generic Malformed on the missing `link`. Prefer the envelope's
            // application-level `status` (e.g. 400) over the transport code, which is often 200 on
            // such a refusal — falling back to the HTTP code when imgur omits it (Codex review #474).
            if (envelope?.success == false) {
                val status = envelope.status.takeIf { it != 0 } ?: resp.code
                throw UploadException.Server(status, id, envelope.data?.errorMessage)
            }
            if (!resp.isSuccessful) throw UploadException.Server(resp.code, id, envelope?.data?.errorMessage)
            val data = (envelope ?: throw UploadException.Malformed(id)).data
                ?: throw UploadException.Malformed(id)
            UploadedImage(
                provider = id,
                imageUrl = data.link ?: throw UploadException.Malformed(id),
                thumbnailUrl = null,
                // imgur exposes size variants via URL suffixes, but the v1 contract does not derive
                // them — the editor's reduced mode falls back to the full link for imgur.
                resizedUrl = null,
                // imgur's deletion contract requires a deletehash; a blank/absent one means deletion
                // is impossible, so surface `null` (canDelete=false) rather than an empty handle that
                // would later DELETE /3/image/ (no id) and falsely promise a removal (#474).
                deleteHandle = data.deleteHash?.takeIf { it.isNotBlank() },
                expiresAt = null,
            )
        }
    }

    override suspend fun delete(deleteHandle: String): Boolean = withContext(ioDispatcher) {
        // Guaranteed deletion (#459): DELETE /3/image/{deletehash}. Suspending Client-ID read (#474).
        val resolvedClientId = userPreferencesRepository.observeImgurClientId().first()
        val request = Request.Builder()
            .url("$baseUrl/3/image/$deleteHandle")
            .header("Authorization", "Client-ID $resolvedClientId")
            .delete()
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    internal companion object {
        /** Named binding key for the imgur API base URL (overridden in tests). */
        const val IMGUR_BASE_URL = "imgur_base_url"
        const val DEFAULT_BASE_URL = "https://api.imgur.com"
        private const val DEFAULT_FILENAME = "upload"

        /**
         * Per-image size cap enforced locally before any POST (#474). imgur documents a 20 MB limit
         * for non-animated images (animated GIFs go up to 200 MB) — see the imgur API upload docs /
         * help centre « What files can I upload? ». The documented figure is decimal MB, so the cap
         * is 20_000_000 bytes, NOT 20 MiB (20*1024*1024 = 20_971_520) — the binary value would let a
         * file between 20_000_001 and 20_971_520 bytes slip past this guard only to be refused by
         * imgur. Decimal is the conservative side for a client-side reject (Codex review #474).
         * Applies to STILL images only — [GIF_MIME] is exempt (imgur GIF limit is 200 MB).
         */
        const val MAX_BYTES = 20_000_000L

        /** imgur accepts animated GIFs up to 200 MB, so the still-image [MAX_BYTES] guard skips them. */
        const val GIF_MIME = "image/gif"
    }
}
