package fr.forumhfr.redface2.core.ui.post

import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.exifinterface.media.ExifInterface
import coil3.Image
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options

/**
 * #959 (Lot 3, contrat v1.5 §3 « autorité des dimensions », cadrage Sol Q1 option b) — a
 * HEADER-ONLY Coil decoder dedicated to the intrinsic-size probe:
 *
 *  - dimensions come from `BitmapFactory.Options.inJustDecodeBounds` (bounds decode, **no bitmap
 *    allocation ever**) — so the probe can report the TRUE native size of a 4000×3000 photo
 *    without paying its decode, where the pre-#959 bounded decode CLIPPED the reported pair to
 *    1024 (measured §3 non-conformity B8);
 *  - the EXIF orientation is applied BY HAND to the reported pair (90°-family orientations swap
 *    width/height) — the full decoder applied it implicitly through the rotated bitmap, a
 *    header-only decode must keep the "oriented native dimensions" contract itself;
 *  - the [DecodeResult] carries a [ProbeMetadataImage]: a metadata-only [Image] (zero reported
 *    size, not shareable, draws nothing). It exists solely so `image.width/height` — the §3
 *    normative source — reach the measurer.
 *
 * The decoder is NOT registered on the ImageLoader: [Factory] is attached PER REQUEST by
 * [measureIntrinsicMediaSize] (`ImageRequest.Builder.decoderFactory`), so no render request can
 * ever hit it; the probe request also disables the memory cache both ways, so the pseudo-image
 * can never be served to a render (pinned by test).
 */
internal class ProbeMetadataDecoder(private val source: ImageSource) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.source().peek().inputStream().use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "not a decodable image (bounds ${bounds.outWidth}x${bounds.outHeight})"
        }
        val swapped = source.source().peek().inputStream().use { input ->
            when (ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE,
                -> true

                else -> false
            }
        }
        val width = if (swapped) bounds.outHeight else bounds.outWidth
        val height = if (swapped) bounds.outWidth else bounds.outHeight
        return DecodeResult(image = ProbeMetadataImage(width, height), isSampled = false)
    }

    object Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = ProbeMetadataDecoder(result.source)
    }
}

/**
 * Metadata-only [Image]: carries the probed dimensions, owns NO pixels (zero reported size),
 * must never be cached across requests ([shareable] = false) and draws nothing if a bug ever
 * routes it to a render — a blank slot is a visible symptom, a crash would take the post down.
 */
private class ProbeMetadataImage(
    override val width: Int,
    override val height: Int,
) : Image {
    override val size: Long get() = 0L
    override val shareable: Boolean get() = false
    override fun draw(canvas: Canvas) = Unit
}
