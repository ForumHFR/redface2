package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ImageViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `cached preview is requested as placeholder and viewer actions are present`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceUrl = "https://images.example.org/full.jpg"
        val previewUrl = "https://images.example.org/thumb.jpg"
        val observedPlaceholderKey = AtomicReference<MemoryCache.Key?>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                observedPlaceholderKey.set(chain.request.placeholderMemoryCacheKey)
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(sourceUrl, ColorImage(0xFF1565C0.toInt(), width = 800, height = 600))
            .build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(recorder)
                add(engine)
            }.build(),
        )
        var closed = false

        try {
            composeTestRule.setContent {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    ImageViewerScreen(
                        request = ImageViewerRequest(
                            sourceUrl = sourceUrl,
                            previewUrl = previewUrl,
                            externalUrl = sourceUrl,
                            description = "photo",
                            diskCache = true,
                        ),
                        onClose = { closed = true },
                        onSave = {},
                    )
                }
            }

            composeTestRule.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).assertExists()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                observedPlaceholderKey.get() != null
            }
            assertEquals(MemoryCache.Key(previewUrl), observedPlaceholderKey.get())

            listOf(
                "Fermer",
                "Partager",
                "Copier l'URL de l'image",
                "Ouvrir dans le navigateur",
                "Enregistrer l'image",
            ).forEach { label ->
                composeTestRule.onNodeWithContentDescription(label).assertExists()
            }
            composeTestRule.onNodeWithContentDescription("Fermer").performClick()
            assertTrue(closed)
        } finally {
            SingletonImageLoader.reset()
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `private viewer keeps Coil disk reads and writes disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceUrl = "https://images.example.org/private.jpg"
        val observedPolicy = AtomicReference<CachePolicy?>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                observedPolicy.set(chain.request.diskCachePolicy)
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(sourceUrl, ColorImage(0xFF2E7D32.toInt(), width = 800, height = 600))
            .build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(recorder)
                add(engine)
            }.build(),
        )

        try {
            composeTestRule.setContent {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    ImageViewerScreen(
                        request = ImageViewerRequest(
                            sourceUrl = sourceUrl,
                            previewUrl = sourceUrl,
                            externalUrl = sourceUrl,
                            description = null,
                            diskCache = false,
                        ),
                        onClose = {},
                        onSave = {},
                    )
                }
            }

            composeTestRule.waitUntil(timeoutMillis = 5_000) { observedPolicy.get() != null }
            assertEquals(CachePolicy.DISABLED, observedPolicy.get())
        } finally {
            SingletonImageLoader.reset()
        }
    }
}
