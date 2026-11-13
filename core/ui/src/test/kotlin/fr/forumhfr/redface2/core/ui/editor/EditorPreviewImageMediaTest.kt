package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.BLOCK_IMAGE_TEST_TAG
import fr.forumhfr.redface2.core.ui.post.DefaultIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.LocalIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.LocalMediaAttemptLedger
import fr.forumhfr.redface2.core.ui.post.MediaAttemptKind
import fr.forumhfr.redface2.core.ui.post.MediaAttemptLedger
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import fr.forumhfr.redface2.core.ui.post.ProcessMediaAttemptLedger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class EditorPreviewImageMediaTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<ImageRequest>()
    private var loader: ImageLoader? = null
    @Volatile private var failRequests = false

    @After fun closeLoader() {
        SingletonImageLoader.reset()
        loader?.shutdown()
    }

    @Test fun `linked preview image uses one inert block and disables disk cache for private drafts`() {
        installLoader(IMAGE_URL)
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalMediaAttemptLedger provides ledger,
                    LocalIntrinsicMediaSizeCache provides cache) {
                    BbcodePreview(linkedImage(IMAGE_URL), mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED)
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { ledger.hasSucceeded(IMAGE_URL, MediaAttemptKind.PAINTER) }
        compose.onAllNodesWithTag(BLOCK_IMAGE_TEST_TAG).assertCountEquals(1)
        compose.onNodeWithTag(BLOCK_IMAGE_TEST_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        assertEquals(listOf(IMAGE_URL), requests.map { it.data })
        assertTrue(requests.all { it.diskCachePolicy == CachePolicy.DISABLED })
    }

    @Test fun `failed preview image retries once on reopening and never on typing`() {
        installLoader(RETRY_URL)
        failRequests = true
        // The public retry seam targets the process ledger, as in production.
        val ledger = ProcessMediaAttemptLedger.instance
        var content by mutableStateOf(linkedImage(RETRY_URL))
        var visible by mutableStateOf(false)
        val body = TextFieldState("draft")
        val focus = FocusRequester()
        val cache = DefaultIntrinsicMediaSizeCache()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    Box(Modifier.size(340.dp, 600.dp)) {
                        EditorPreview(visible, content, body, focus, PostMediaDiskCachePolicy.DISABLED, "Aperçu") {
                            BbcodeTextField(body, "Message")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(requests.isEmpty()); visible = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            ledger.isFailedFresh(RETRY_URL, MediaAttemptKind.PAINTER, System.currentTimeMillis())
        }
        compose.runOnIdle {
            assertEquals(listOf(RETRY_URL), requests.map { it.data })
            body.edit { append(" typed") }
            content = PostContent(content.blocks + PostBlock.Paragraph(listOf(PostInline.Text("typed"))))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("typing must not retry failed images", 1, requests.size)
            assertEquals(0, ledger.generationOf(RETRY_URL))
            failRequests = false
            visible = false
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { ledger.hasSucceeded(RETRY_URL, MediaAttemptKind.PAINTER) }
        compose.runOnIdle {
            assertEquals(listOf(RETRY_URL, RETRY_URL), requests.map { it.data })
            assertEquals(1, ledger.generationOf(RETRY_URL))
            assertTrue(requests.all { it.diskCachePolicy == CachePolicy.DISABLED })
        }
    }

    @Test fun `opening preview retries only failed media in the current content`() {
        installLoader(CURRENT_URL)
        val ledger = ProcessMediaAttemptLedger.instance
        listOf(PREVIOUS_URL, CURRENT_URL).forEach { url ->
            ledger.settleFailure(url, 0, MediaAttemptKind.PAINTER, System.currentTimeMillis())
        }
        var content by mutableStateOf(linkedImage(PREVIOUS_URL))
        var visible by mutableStateOf(false)
        val body = TextFieldState("draft")
        val focus = FocusRequester()
        val cache = DefaultIntrinsicMediaSizeCache()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    Box(Modifier.size(340.dp, 600.dp)) {
                        EditorPreview(visible, content, body, focus, PostMediaDiskCachePolicy.DISABLED, "Aperçu") {
                            BbcodeTextField(body, "Message")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { content = linkedImage(CURRENT_URL) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(0, ledger.generationOf(CURRENT_URL))
            visible = true
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { ledger.hasSucceeded(CURRENT_URL, MediaAttemptKind.PAINTER) }
        compose.runOnIdle {
            assertEquals(listOf(CURRENT_URL), requests.map { it.data })
            assertEquals(1, ledger.generationOf(CURRENT_URL))
            assertEquals("media removed from the draft must stay untouched", 0, ledger.generationOf(PREVIOUS_URL))
        }
    }

    private fun installLoader(url: String) {
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                requests += chain.request
                return if (failRequests) {
                    ErrorResult(null, chain.request, IllegalStateException("offline image"))
                } else {
                    chain.proceed()
                }
            }
        }
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(width = 70, height = 50)).build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageLoader = ImageLoader.Builder(context).components { add(recorder); add(engine) }.build()
        loader = imageLoader
        SingletonImageLoader.setUnsafe(imageLoader)
    }

    private fun linkedImage(url: String) = PostContent(listOf(PostBlock.Paragraph(listOf(
        PostInline.Link("https://h/full.jpg", listOf(PostInline.InlineImage(url, null))),
    ))))
}

private const val IMAGE_URL = "https://images.example.org/editor-preview-linked.jpg"
private const val RETRY_URL = "https://images.example.org/editor-preview-retry.jpg"
private const val PREVIOUS_URL = "https://images.example.org/editor-preview-previous.jpg"
private const val CURRENT_URL = "https://images.example.org/editor-preview-current.jpg"
