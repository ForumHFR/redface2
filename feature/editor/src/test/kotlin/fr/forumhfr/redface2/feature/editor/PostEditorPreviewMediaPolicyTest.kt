package fr.forumhfr.redface2.feature.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ADR-018 § 9 through the real screen: the requests issued by the preview carry the target's policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class PostEditorPreviewMediaPolicyTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<ImageRequest>()
    private var loader: ImageLoader? = null

    @After fun closeLoader() {
        SingletonImageLoader.reset()
        loader?.shutdown()
    }

    @Test fun `private message edit preview never uses the disk cache`() {
        val state = PostEditorState(PostEditorMode.Edit, null, null, 101, 3, null,
            target = PostEditorTarget.PrivateMessage(42))
        assertEquals(setOf(CachePolicy.DISABLED), previewDiskPolicies(state, PRIVATE_URL))
    }

    @Test fun `public reply preview keeps the disk cache`() {
        val state = PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0)
        assertEquals(setOf(CachePolicy.ENABLED), previewDiskPolicies(state, PUBLIC_URL))
    }

    private fun previewDiskPolicies(base: PostEditorState, url: String): Set<CachePolicy> {
        installLoader(url)
        val content = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.InlineImage(url, null)))))
        var state by mutableStateOf(base.copy(bodyText = "[img]$url[/img]", preview = content,
            hasLoadedForm = true, hasAuthenticatedForm = true, draftHydratedFromForm = true))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostEditorTestContent(state)
            }
        }
        compose.runOnIdle {
            assertTrue("a hidden preview requests no media", requests.isEmpty())
            state = state.copy(isPreviewVisible = true)
        }
        // Drain Robolectric's paused main looper after the snapshot write, then wait for the renderer.
        compose.waitForIdle()
        compose.waitUntil(5_000) { requests.any { it.data == url } }
        compose.waitForIdle()
        compose.onNodeWithTag("post-editor-preview").assertExists()
        return requests.filter { it.data == url }.map { it.diskCachePolicy }.toSet()
    }

    private fun installLoader(url: String) {
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                requests += chain.request
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(width = 70, height = 50)).build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageLoader = ImageLoader.Builder(context).components { add(recorder); add(engine) }.build()
        loader = imageLoader
        SingletonImageLoader.setUnsafe(imageLoader)
    }
}

// Distinct URLs: the renderer's attempt ledger is process-wide and outlives each test.
private const val PRIVATE_URL = "https://images.example.org/post-editor-private-preview.jpg"
private const val PUBLIC_URL = "https://images.example.org/post-editor-public-preview.jpg"
