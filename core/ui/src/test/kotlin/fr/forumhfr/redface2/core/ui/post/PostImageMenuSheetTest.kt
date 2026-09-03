package fr.forumhfr.redface2.core.ui.post

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #831/#1040 — pins the entries of the shared image contextual menu: « Enregistrer » routes
 * to the save callback, « Partager » routes to the share callback, « Copier l'URL » writes the
 * clipboard, « Ouvrir dans le navigateur » fires an ACTION_VIEW on the DIRECT image URL, and
 * « Afficher en taille réelle » stays a DISABLED placeholder until the fullscreen viewer (#182).
 *
 * The target URL uses the reserved `.invalid` TLD so the hero thumbnail's Coil request fails
 * fast without touching the network — the sheet never blocks on the bitmap by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostImageMenuSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val target = PostImageTarget(
        url = "https://images.invalid/photos/vacances.png",
        description = "photo",
        linkUrl = null,
    )

    private fun mount(
        onSave: (String) -> Unit = {},
        onShare: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
        mediaDiskCachePolicy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostImageMenuSheet(
                    target = target,
                    onSave = onSave,
                    onShare = onShare,
                    onDismiss = onDismiss,
                    mediaDiskCachePolicy = mediaDiskCachePolicy,
                )
            }
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `private-media thumbnail disables the Coil disk cache`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val observed = CopyOnWriteArrayList<CachePolicy>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.data == target.url) observed += chain.request.diskCachePolicy
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(target.url, ColorImage(width = 64, height = 64))
            .build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(recorder)
                add(engine)
            }.build(),
        )

        try {
            mount(mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED)
            composeTestRule.waitUntil(timeoutMillis = 5_000) { observed.isNotEmpty() }

            assertEquals(setOf(CachePolicy.DISABLED), observed.toSet())
        } finally {
            SingletonImageLoader.reset()
        }
    }

    @Test
    fun `the save entry routes the image URL to the save callback`() {
        val saved = mutableListOf<String>()
        mount(onSave = { saved += it })

        composeTestRule.onNodeWithText("Enregistrer l'image").performClick()

        assertEquals(listOf(target.url), saved)
    }

    @Test
    fun `the share entry routes the image URL to the share callback`() {
        val shared = mutableListOf<String>()
        mount(onShare = { shared += it })

        composeTestRule.onNodeWithText("Partager").performClick()

        assertEquals(listOf(target.url), shared)
    }

    @Test
    fun `the share helper opens an ACTION_SEND chooser with the image URL`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        composeTestRule.setContent {
            val context = LocalContext.current
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostImageMenuSheet(
                    target = target,
                    onSave = {},
                    onShare = { url -> sharePostImageUrl(context, url, "échec") },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Partager").performClick()

        val started = Shadows.shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val send = requireNotNull(started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(target.url, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the copy entry writes the image URL to the clipboard`() {
        mount()

        composeTestRule.onNodeWithText("Copier l'URL de l'image").performClick()

        val clipboard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(target.url, copied)
    }

    @Test
    fun `the browser entry keeps the direct image URL inside the external chooser`() {
        mount()

        composeTestRule.onNodeWithText("Ouvrir dans le navigateur").performClick()

        val started = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val view = requireNotNull(started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(target.url, view.data.toString())
    }

    @Test
    fun `the full-size entry is a disabled placeholder`() {
        mount()

        composeTestRule.onNodeWithText("Afficher en taille réelle (à venir)")
            .assertIsNotEnabled()
    }
}
