package fr.forumhfr.redface2.core.domain.upload

/**
 * Typed failure surface of an image upload (#459), modelled on the `:core:domain` error style of
 * #324 ([fr.forumhfr.redface2.core.domain.error.HfrServerException]). Lives in `:core:domain` so a
 * feature module (the editor, PR2) can type-check the failure cause without importing `:core:data`
 * or `:core:network`, which the Konsist architecture test forbids.
 *
 * Distinguishing the cases lets the UI render an actionable message (too large vs. unsupported type
 * vs. host outage) instead of a generic « upload failed ».
 */
sealed class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The picked image exceeds the provider's accepted size ([maxBytes] is the cap that was hit). */
    class TooLarge(val maxBytes: Long) : UploadException("Image exceeds $maxBytes bytes")

    /** The picked image MIME type is not accepted by the provider. */
    class UnsupportedType(val mimeType: String?) : UploadException("Unsupported image type: $mimeType")

    /**
     * The host refused the upload at the protocol level. [code] is the HTTP status, [providerId] the
     * host. [errorMessage] is the host-supplied reason when one was parsed (imgur surfaces it in the
     * `data.error` field of a `success:false` envelope, #474) — `null` when the host gave none. It is
     * an OPTIONAL, retro-compatible addition: existing call sites that only read [code]/[providerId]
     * keep compiling unchanged.
     */
    class Server(
        val code: Int,
        val providerId: UploadProviderId,
        val errorMessage: String? = null,
    ) : UploadException(
        if (errorMessage != null) "HTTP $code ($providerId): $errorMessage" else "HTTP $code ($providerId)",
    )

    /** The host answered 2xx but the body could not be parsed into the expected shape. */
    class Malformed(val providerId: UploadProviderId, cause: Throwable? = null) :
        UploadException("Unreadable response ($providerId)", cause)

    /** The exchange never produced an HTTP response (airplane mode, DNS, timeout). */
    class Network(cause: Throwable) : UploadException("Network unavailable", cause)

    /**
     * The provider is mis-configured and the upload cannot even be attempted (#474) — e.g. the
     * imgur Client-ID preference is empty/blank. Raised LOCALLY, before any network call, so the
     * user gets an actionable « configure the host » message instead of an opaque HTTP 400/403.
     * [detail] is a short, non-secret explanation safe to log/surface. Distinct from [Server], which
     * means the host was reached and refused.
     */
    class Configuration(val detail: String) : UploadException("Upload provider misconfigured: $detail")
}
