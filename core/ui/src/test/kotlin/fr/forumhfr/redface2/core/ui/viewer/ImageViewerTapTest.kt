package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ImageViewerTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var request by mutableStateOf(
        ImageViewerRequest(SOURCE_URL, SOURCE_URL, SOURCE_URL, "photo", diskCache = false),
    )
    private var closeCount = 0
    private var doubleTapTimeoutMillis = 0L

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    @Test
    fun `single taps toggle public image actions and close still works`() = verifySingleTaps(diskCache = true)

    @Test
    fun `single taps toggle private image actions and close still works`() = verifySingleTaps(diskCache = false)

    @Test
    fun `double taps preserve public image action visibility`() = verifyDoubleTaps(diskCache = true)

    @Test
    fun `double taps preserve private image action visibility`() = verifyDoubleTaps(diskCache = false)

    @Test
    fun `public image exposes labelled accessibility actions`() = verifyAccessibility(diskCache = true)

    @Test
    fun `private image exposes labelled accessibility actions`() = verifyAccessibility(diskCache = false)

    @Test
    fun `new public image restores actions and accepts another tap`() = verifyNewImage(diskCache = true)

    @Test
    fun `new private image restores actions and accepts another tap`() = verifyNewImage(diskCache = false)

    @Test
    fun `hiding actions leaves the loading overlay visible`() {
        val loadGate = CompletableDeferred<Unit>()
        mountViewer(loadGate = loadGate)
        compose.onNodeWithTag(IMAGE_VIEWER_LOADING_TAG).assertIsDisplayed()

        singleTap()

        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(IMAGE_VIEWER_LOADING_TAG).assertIsDisplayed()
        loadGate.complete(Unit)
        waitForImage()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun `hiding actions leaves the error overlay visible`() {
        mountViewer(failLoading = true)
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithTag(IMAGE_VIEWER_ERROR_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the image outside the centered error panel and its browser button.
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput { click(Offset(EDGE_INSET_PX, EDGE_INSET_PX)) }
        settleGestures()

        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(IMAGE_VIEWER_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.image_viewer_close)).assertDoesNotExist()
    }

    @Test
    fun `disabled system animations remove the bar without a fade`() {
        val resolver = context.contentResolver
        val previousScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            mountViewer()
            waitForImage()
            compose.mainClock.autoAdvance = false

            compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performSemanticsAction(SemanticsActions.OnClick) { it() }
            // Allow recomposition and transition disposal, but less time than the 150 ms fade.
            compose.mainClock.advanceTimeUntil(timeoutMillis = DISABLED_ANIMATION_SETTLE_MS) {
                compose.onAllNodesWithTag(IMAGE_VIEWER_ACTIONS_TAG).fetchSemanticsNodes().isEmpty()
            }

            compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
        }
    }

    private fun verifySingleTaps(diskCache: Boolean) {
        mountViewer(diskCache = diskCache)
        waitForImage()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()

        singleTap()

        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.image_viewer_close)).assertDoesNotExist()
        // The former close-button area must now pass gestures through to the image.
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            click(Offset(width - EDGE_INSET_PX, height - EDGE_INSET_PX))
        }
        settleGestures()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()
        assertEquals(0, closeCount)
        compose.onNodeWithContentDescription(context.getString(R.string.image_viewer_close)).performClick()
        assertEquals(1, closeCount)
    }

    private fun verifyDoubleTaps(diskCache: Boolean) {
        mountViewer(diskCache = diskCache)
        waitForImage()

        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput { doubleClick() }
        settleGestures()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()

        singleTap()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput { doubleClick() }
        settleGestures()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        singleTap()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()
    }

    private fun verifyAccessibility(diskCache: Boolean) {
        mountViewer(diskCache = diskCache)
        waitForImage()
        val image = compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG)
        image.assertContentDescriptionEquals("photo").assertHasClickAction()
        assertEquals(
            context.getString(R.string.image_viewer_hide_actions),
            image.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )

        // Android's performClick injects touch events; invoke the TalkBack action explicitly here.
        image.performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
        image.assertContentDescriptionEquals("photo").assertHasClickAction()
        assertEquals(
            context.getString(R.string.image_viewer_show_actions),
            image.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        image.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()
    }

    private fun verifyNewImage(diskCache: Boolean) {
        mountViewer(diskCache = diskCache)
        waitForImage()
        singleTap()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()

        compose.runOnIdle { request = request.copy(sourceUrl = NEXT_SOURCE_URL) }
        waitForImage()

        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertIsDisplayed()
        singleTap()
        compose.onNodeWithTag(IMAGE_VIEWER_ACTIONS_TAG).assertDoesNotExist()
    }

    private fun mountViewer(
        diskCache: Boolean = false,
        loadGate: CompletableDeferred<Unit>? = null,
        failLoading: Boolean = false,
    ) {
        request = request.copy(diskCache = diskCache)
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(SOURCE_URL, ColorImage(android.graphics.Color.BLUE, width = 360, height = 780))
            .intercept(NEXT_SOURCE_URL, ColorImage(android.graphics.Color.GREEN, width = 360, height = 780))
            .build()
        val interceptor = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                loadGate?.await()
                check(!failLoading) { "Image load failure for the viewer overlay test" }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(interceptor)
                add(engine)
            }.build(),
        )
        compose.setContent {
            val configuration = LocalViewConfiguration.current
            SideEffect { doubleTapTimeoutMillis = configuration.doubleTapTimeoutMillis }
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ImageViewerScreen(request = request, onClose = { closeCount++ }, onSave = {})
            }
        }
    }

    private fun waitForImage() {
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithTag(IMAGE_VIEWER_LOADING_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(IMAGE_VIEWER_ERROR_TAG).assertDoesNotExist()
    }

    private fun singleTap() {
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput { click() }
        settleGestures()
    }

    private fun settleGestures() {
        // A real tap must outlive Telephoto's double-tap window, then the action-bar fade.
        compose.mainClock.advanceTimeBy(doubleTapTimeoutMillis + ANIMATION_SETTLE_MS)
        compose.waitForIdle()
    }
}

private const val SOURCE_URL = "https://images.example.org/tap.jpg"
private const val NEXT_SOURCE_URL = "https://images.example.org/next.jpg"
private const val LOAD_TIMEOUT_MS = 5_000L
private const val ANIMATION_SETTLE_MS = 200L
private const val DISABLED_ANIMATION_SETTLE_MS = 100L
private const val EDGE_INSET_PX = 20f
