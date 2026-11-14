package fr.forumhfr.redface2.feature.editor

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import java.io.IOException

/** Intercepts every image request without network access; callers reset the singleton after each test. */
@OptIn(DelicateCoilApi::class)
internal fun installMessageIconPickerTestImages(offline: Boolean = false) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val builder = FakeImageLoaderEngine.Builder()
    if (offline) {
        builder.default { chain ->
            ErrorResult(image = null, request = chain.request, throwable = IOException("Offline"))
        }
    } else {
        // Synthetic pixel art, not an HFR fixture: edges make accidental filtering visible in the captures.
        val bitmap = Bitmap.createBitmap(15, 15, Bitmap.Config.ARGB_8888)
        for (y in 0 until 15) for (x in 0 until 15) {
            bitmap.setPixel(x, y, if ((x + y) % 2 == 0) 0xFF1565C0.toInt() else 0xFFFFC107.toInt())
        }
        builder.default(bitmap.asImage())
    }
    SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(builder.build()) }.build())
}
