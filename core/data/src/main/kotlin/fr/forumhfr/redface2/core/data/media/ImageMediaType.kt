package fr.forumhfr.redface2.core.data.media

/**
 * #831 — a saved image's MediaStore identity: the MIME type recorded on the row and the file
 * extension appended to the display name. Always produced as a PAIR by [resolveImageMediaType] so
 * the two can never disagree (the arbitrated contract: « extension cohérente avec le MIME sniffé »).
 */
internal data class ImageMediaType(val mimeType: String, val extension: String)

private val JPEG = ImageMediaType("image/jpeg", "jpg")
private val PNG = ImageMediaType("image/png", "png")
private val GIF = ImageMediaType("image/gif", "gif")
private val WEBP = ImageMediaType("image/webp", "webp")
private val BMP = ImageMediaType("image/bmp", "bmp")
private val AVIF = ImageMediaType("image/avif", "avif")

private val EXTENSION_TYPES = mapOf(
    "jpg" to JPEG,
    "jpeg" to JPEG,
    "png" to PNG,
    "gif" to GIF,
    "webp" to WEBP,
    "bmp" to BMP,
    "avif" to AVIF,
)

/**
 * #831 — resolves the [ImageMediaType] of the ORIGINAL bytes about to be saved. Priority order:
 *
 *  1. magic-byte sniffing ([sniffImageMediaType]) — the bytes themselves are the truth (an image
 *     host can serve a PNG behind a `.jpg` URL);
 *  2. the URL's file extension — covers formats the sniffer doesn't know;
 *  3. a STABLE fallback (JPEG) so the save never fails just because the type is exotic — a wrong
 *     label is recoverable, a refused save is not.
 *
 * Pure (bytes + string in, value out) so the whole decision is pinned by a JVM test.
 */
internal fun resolveImageMediaType(bytes: ByteArray, url: String): ImageMediaType =
    sniffImageMediaType(bytes) ?: imageMediaTypeFromUrl(url) ?: JPEG

/**
 * Sniffs the common raster formats HFR image hosts serve, from their magic bytes:
 * JPEG (`FF D8 FF`), PNG (`89 50 4E 47`), GIF (`GIF8`), WebP (`RIFF….WEBP`), BMP (`BM`) and
 * AVIF (`ftypavif` at offset 4). Returns null when no signature matches (caller falls back).
 */
@Suppress("ReturnCount", "MagicNumber") // signature table — early returns ARE the table.
internal fun sniffImageMediaType(bytes: ByteArray): ImageMediaType? {
    if (bytes.size < 12) return null
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return JPEG
    if (bytes[0] == 0x89.toByte() && bytes.startsWithAscii(1, "PNG")) return PNG
    if (bytes.startsWithAscii(0, "GIF8")) return GIF
    if (bytes.startsWithAscii(0, "RIFF") && bytes.startsWithAscii(8, "WEBP")) return WEBP
    if (bytes.startsWithAscii(0, "BM")) return BMP
    if (bytes.startsWithAscii(4, "ftypavif")) return AVIF
    return null
}

private fun ByteArray.startsWithAscii(offset: Int, ascii: String): Boolean {
    if (size < offset + ascii.length) return false
    return ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }
}

/** Media type from the URL path's extension (query/fragment stripped), null when unknown. */
internal fun imageMediaTypeFromUrl(url: String): ImageMediaType? {
    val path = url.substringBefore('?').substringBefore('#')
    val lastSegment = path.substringAfterLast('/')
    val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
    return EXTENSION_TYPES[extension.lowercase()]
}

/** Display-name base cap — generous for findability, well under MediaStore's 255-byte limit. */
private const val MAX_BASE_NAME_LENGTH = 64

/** Characters allowed verbatim in a saved file's base name; anything else becomes `_`. */
private fun Char.isAllowedInFileName(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

/**
 * #831 — display name of the saved file: the URL's last path segment without its extension,
 * sanitized to a safe charset, capped, with the [mediaType]'s extension appended (so name and
 * MIME always agree). An unusable base (empty path, all-special characters) falls back to the
 * stable `"image"` — MediaStore de-duplicates colliding display names on its own (` (1)` suffix).
 */
internal fun imageDisplayName(url: String, mediaType: ImageMediaType): String {
    val path = url.substringBefore('?').substringBefore('#')
    val lastSegment = path.substringAfterLast('/')
    val rawBase = lastSegment.substringBeforeLast('.', missingDelimiterValue = lastSegment)
    val sanitized = rawBase
        .map { char -> if (char.isAllowedInFileName()) char else '_' }
        .joinToString(separator = "")
        .trim { it == '.' || it == '_' }
        .take(MAX_BASE_NAME_LENGTH)
    val base = sanitized.ifEmpty { "image" }
    return "$base.${mediaType.extension}"
}
