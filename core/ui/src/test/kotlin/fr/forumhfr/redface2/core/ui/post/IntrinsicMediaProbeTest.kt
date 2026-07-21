package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import java.io.File
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #959 (Lot 3, contrat v1.5 §3 « autorité des dimensions ») — the intrinsic probe reports the
 * NATIVE ORIENTED source dimensions through a header-only decode ([ProbeMetadataDecoder], cadrage
 * Sol Q1 option b): no full-bitmap allocation, EXIF orientation applied to the reported pair, and
 * the probe's pseudo-image never pollutes the render memory cache. The pre-#959 probe CLIPPED the
 * reported dimensions to its 1024-bounded decode (measured: 4000×3000 → 1024×768) — the exact
 * §3 non-conformity this decoder removes. Exercised on REAL local files (the fake engine
 * intercepts before the fetch, so it can never exercise a decoder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntrinsicMediaProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pngFile(width: Int, height: Int): File {
        val file = File.createTempFile("probe", ".png")
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun jpegFile(width: Int, height: Int, exifOrientation: Int? = null): File {
        val file = File.createTempFile("probe", ".jpg")
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        if (exifOrientation != null) {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
        }
        return file
    }

    private fun loader(): ImageLoader = ImageLoader.Builder(context).build()

    @Test
    fun `the probe reports native dimensions PAST the former 1024 bound - no clipping`() = runTest {
        // 2000×1500 source: the pre-#959 bounded decode reported 1024×768 (measured, §3
        // non-conformity B8). The header-only probe must report the true 2000×1500.
        val file = pngFile(2000, 1500)
        val size = measureIntrinsicMediaSize(file.absolutePath, context, loader())
        assertEquals(IntSize(2000, 1500), size)
    }

    @Test
    fun `the probe applies the EXIF orientation to the reported pair`() = runTest {
        // A 64×32 JPEG carrying ORIENTATION_ROTATE_90 is a PORTRAIT 32×64 source once oriented —
        // §3: wNatif/hNatif are the ORIENTED native dimensions (confirmed by measure on the old
        // full-decode path; the header-only decode must keep that contract by hand).
        val file = jpegFile(64, 32, exifOrientation = ExifInterface.ORIENTATION_ROTATE_90)
        val size = measureIntrinsicMediaSize(file.absolutePath, context, loader())
        assertEquals(IntSize(32, 64), size)
    }

    @Test
    fun `the probe decode carries no bitmap - a metadata image of size zero`() = runTest {
        // Mini-gate Sol P2 (structural proof): the DecodeResult image is the metadata carrier —
        // zero reported byte size, not shareable, and NOT a BitmapImage. The decoder is exercised
        // directly on an okio ImageSource, exactly what the per-request factory hands it.
        val file = pngFile(800, 600)
        val source = coil3.decode.ImageSource(
            file = file.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        )
        val result = source.use { ProbeMetadataDecoder(it).decode() }
        assertEquals(0L, result.image.size)
        assertTrue("the probe image must not be a bitmap", result.image !is BitmapImage)
        assertTrue("the probe pseudo-image must never be shareable", !result.image.shareable)
    }

    @Test
    fun `a render request after a probe still decodes a REAL bitmap - no cache pollution`() = runTest {
        // The probe disables the memory cache on its own request; a subsequent normal render
        // request on the SAME data must produce a real bitmap, never the metadata pseudo-image.
        val file = pngFile(120, 90)
        val imageLoader = loader()
        val probed = measureIntrinsicMediaSize(file.absolutePath, context, imageLoader)
        assertEquals(IntSize(120, 90), probed)

        val render = imageLoader.execute(
            coil3.request.ImageRequest.Builder(context).data(file.absolutePath).build(),
        )
        val image = (render as coil3.request.SuccessResult).image
        assertTrue("render must decode a real bitmap after a probe", image is BitmapImage)
    }

    @Test
    fun `a REAL gif probes successfully - the exif read must never fail the whole probe`() = runTest {
        // Gate Sol r1 (blocker #1): ExifInterface does not support GIF — an unguarded call made
        // the probe fail, so a real GIF never got a measured box (nor an animation). The classic
        // minimal transparent 1×1 GIF89a exercises the real decoder path end to end.
        val gifBytes = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // "GIF89a"
            0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, // 1×1, 2-colour palette
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // palette
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, // GCE
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // image descriptor
            0x02, 0x02, 0x44, 0x01, 0x00, // image data
            0x3B, // trailer
        )
        val file = File.createTempFile("probe", ".gif").apply {
            writeBytes(gifBytes)
            deleteOnExit()
        }
        val size = measureIntrinsicMediaSize(file.absolutePath, context, loader())
        assertEquals(IntSize(1, 1), size)
    }

    @Test
    fun `an unreadable source reports null - same failure contract as before`() = runTest {
        val file = File.createTempFile("probe", ".png").apply {
            writeText("not an image")
            deleteOnExit()
        }
        val size = measureIntrinsicMediaSize(file.absolutePath, context, loader())
        assertEquals(null, size)
    }
}
