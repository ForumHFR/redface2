package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
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

    private lateinit var hostView: View
    private lateinit var density: Density

    @OptIn(DelicateCoilApi::class)
    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

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
        var savedUrl: String? = null

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
                        onSave = { savedUrl = it },
                    )
                }
            }

            composeTestRule.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).assertExists()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                observedPlaceholderKey.get() != null
            }
            assertEquals(MemoryCache.Key(previewUrl), observedPlaceholderKey.get())

            val labels = listOf(
                R.string.post_image_menu_share,
                R.string.post_image_menu_copy_url,
                R.string.browser_open_action,
                R.string.post_image_menu_save,
                R.string.image_viewer_close,
            ).map(context::getString)
            val actions = composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).onChildren()
            actions.assertCountEquals(labels.size)
            labels.forEachIndexed { index, label ->
                actions[index]
                    .assertContentDescriptionEquals(label)
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertWidthIsEqualTo(48.dp)
                    .assertHeightIsEqualTo(48.dp)
            }
            val saveBounds = actions[labels.lastIndex - 1].getUnclippedBoundsInRoot()
            val closeBounds = actions[labels.lastIndex].getUnclippedBoundsInRoot()
            val barBounds = composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).getUnclippedBoundsInRoot()
            assertTrue(closeBounds.left > saveBounds.right)
            assertEquals(barBounds.right - 4.dp, closeBounds.right)
            actions[labels.lastIndex - 1].performClick()
            assertEquals(sourceUrl, savedUrl)
            actions[labels.lastIndex].performClick()
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

    @Test
    fun `hidden bars with a persistent top cutout keep the action bar at 56 dp`() {
        mountViewer()
        applyInsets(topCutout = 48.dp)

        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertHeightIsEqualTo(56.dp)
    }

    @Test
    fun `visible navigation adds its bottom inset once and hiding removes it`() {
        mountViewer()
        applyInsets(topCutout = 48.dp)
        val close = closeAction().getUnclippedBoundsInRoot()

        applyInsets(topCutout = 48.dp, navBottom = 48.dp, navigationVisible = true)

        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertHeightIsEqualTo(104.dp)
        assertEquals(close.top - 48.dp, closeAction().getUnclippedBoundsInRoot().top)

        applyInsets(topCutout = 48.dp)

        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertHeightIsEqualTo(56.dp)
        assertEquals(close, closeAction().getUnclippedBoundsInRoot())
    }

    @Test
    fun `navigation already consumed by a parent is not added again`() {
        mountViewer(consumeNavigation = true)
        applyInsets(navBottom = 48.dp, navigationVisible = true)

        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertHeightIsEqualTo(56.dp)
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-xxhdpi")
    fun `landscape side cutout and navigation protect actions without growing the bar`() {
        mountViewer()
        applyInsets()
        val close = closeAction().getUnclippedBoundsInRoot()

        // Overlapping cutout and navigation on the same side use their maximum, not their sum.
        applyInsets(sideCutout = 24.dp, navRight = 48.dp, navigationVisible = true)

        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertHeightIsEqualTo(56.dp)
        assertEquals(close.right - 48.dp, closeAction().getUnclippedBoundsInRoot().right)

        applyInsets(sideCutout = 24.dp)

        assertEquals(close.right - 24.dp, closeAction().getUnclippedBoundsInRoot().right)
    }

    // Record-only, like the other :core:ui captures: testDebugUnitTest enables roborazzi.test.record.
    // Light/dark refer to the image behind the translucent controls, not the app theme.
    @Test
    fun recordLightImage() = recordViewer(imageColor = android.graphics.Color.WHITE, suffix = "light")

    @Test
    fun recordDarkImage() = recordViewer(imageColor = android.graphics.Color.BLACK, suffix = "dark")

    private fun recordViewer(imageColor: Int, suffix: String) {
        mountViewer(imageColor = imageColor)
        applyInsets(topCutout = 48.dp)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(IMAGE_VIEWER_LOADING_TAG).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithTag(IMAGE_VIEWER_ERROR_TAG).assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/image_viewer_actions_$suffix.png",
        )
    }

    @OptIn(DelicateCoilApi::class)
    private fun mountViewer(imageColor: Int = android.graphics.Color.BLACK, consumeNavigation: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceUrl = "https://images.example.org/insets.jpg"
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(sourceUrl, ColorImage(imageColor, width = 360, height = 780))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
        composeTestRule.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            SideEffect {
                hostView = view
                density = currentDensity
            }
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ImageViewerScreen(
                    request = ImageViewerRequest(
                        sourceUrl = sourceUrl,
                        previewUrl = sourceUrl,
                        externalUrl = sourceUrl,
                        description = "photo",
                        diskCache = false,
                    ),
                    onClose = {},
                    onSave = {},
                    modifier = if (consumeNavigation) {
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    } else {
                        Modifier
                    },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    // Exercises Compose's real inset listener. This cannot reproduce Samsung's system-bar
    // animation/timing; hidden navigation retains only its ignoring-visibility size (48 dp).
    private fun applyInsets(
        topCutout: Dp = 0.dp,
        sideCutout: Dp = 0.dp,
        navBottom: Dp = 0.dp,
        navRight: Dp = 0.dp,
        navigationVisible: Boolean = false,
    ) {
        val insets = with(density) {
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.statusBars(), false)
                .setInsets(
                    WindowInsetsCompat.Type.displayCutout(),
                    Insets.of(0, topCutout.roundToPx(), sideCutout.roundToPx(), 0),
                )
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, navRight.roundToPx(), navBottom.roundToPx()),
                )
                .setInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, navRight.roundToPx(), 48.dp.roundToPx()),
                )
                .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationVisible)
                .build()
        }
        composeTestRule.runOnIdle { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        composeTestRule.waitForIdle()
    }

    private fun closeAction() = composeTestRule.onNodeWithContentDescription(
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.image_viewer_close),
    )
}
