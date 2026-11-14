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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import fr.forumhfr.redface2.core.parser.BbcodeContentParser
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.DefaultIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.LocalIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.LocalMediaAttemptLedger
import fr.forumhfr.redface2.core.ui.post.MediaAttemptKind
import fr.forumhfr.redface2.core.ui.post.MediaAttemptLedger
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
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

/** ED-66 interpretation pending ratification: URL discovery is pure; displayed sprites are media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class EditorPreviewSmileyMediaTest {
    @get:Rule val compose = createComposeRule()
    private var loader: ImageLoader? = null

    @After fun closeLoader() {
        SingletonImageLoader.reset()
        loader?.shutdown()
    }

    @Test fun `public preview only requests displayed sprite media without URL search`() =
        checkMedia(PostMediaDiskCachePolicy.ENABLED)

    @Test fun `private preview disables disk cache for sprite media and probes without URL search`() =
        checkMedia(PostMediaDiskCachePolicy.DISABLED)

    private fun checkMedia(policy: PostMediaDiskCachePolicy) {
        val requests = CopyOnWriteArrayList<ImageRequest>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                requests += chain.request
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(FIRST_URL, ColorImage(width = 70, height = 50))
            .intercept(SECOND_URL, ColorImage(width = 70, height = 50)).build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageLoader = ImageLoader.Builder(context).components { add(recorder); add(engine) }.build()
        loader = imageLoader
        SingletonImageLoader.setUnsafe(imageLoader)
        val parser = BbcodeContentParser()
        var content by mutableStateOf(parser.parse("Bonjour"))
        var visible by mutableStateOf(false)
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        val body = TextFieldState("Bonjour")
        val focus = FocusRequester()
        fun type(text: String) {
            body.edit { replace(0, length, text) }
            content = parser.parse(text)
        }
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalMediaAttemptLedger provides ledger,
                    LocalIntrinsicMediaSizeCache provides cache) {
                    Box(Modifier.size(340.dp, 600.dp)) {
                        EditorPreview(visible, content, body, focus, policy, "Aperçu") {
                            BbcodeTextField(body, "Message")
                        }
                    }
                }
            }
        }
        // Even complete tokens derive their URLs without any renderer/network work while hidden.
        compose.runOnIdle { type("Bonjour [:fictif un]") }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requests.isEmpty()); visible = true }
        // As in PostRendererContentGeometryTest, drain Robolectric's paused main looper after
        // snapshot writes: polling the ledger alone does not run the preview's measure/subcompose.
        compose.waitForIdle()
        compose.waitUntil(5_000) { ledger.hasSucceeded(FIRST_URL, MediaAttemptKind.PAINTER) }
        assertEquals(setOf(FIRST_URL), requests.map { it.data }.toSet())
        // Completing another token while open may load only that sprite, never a search endpoint.
        compose.runOnIdle { type("Bonjour [:fictif un] [:fictif deux") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(setOf(FIRST_URL), requests.map { it.data }.toSet()) }
        compose.runOnIdle { type("Bonjour [:fictif un] [:fictif deux]") }
        compose.waitForIdle()
        compose.waitUntil(5_000) { ledger.hasSucceeded(SECOND_URL, MediaAttemptKind.PAINTER) }
        assertEquals(setOf(FIRST_URL, SECOND_URL), requests.map { it.data }.toSet())
        val expected = if (policy == PostMediaDiskCachePolicy.DISABLED) CachePolicy.DISABLED else CachePolicy.ENABLED
        assertTrue("every sprite request, probes included, uses the host disk policy",
            requests.all { it.diskCachePolicy == expected })
        val count = requests.size
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { type("Bonjour [:fictif trois]") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("hidden typing must not request media", count, requests.size) }
    }
}

private const val FIRST_URL = "https://forum-images.hardware.fr/images/perso/fictif un.gif"
private const val SECOND_URL = "https://forum-images.hardware.fr/images/perso/fictif deux.gif"
