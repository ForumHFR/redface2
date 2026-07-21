package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #959 (Lot 3, contrat v1.5 §3 GIF, cadrage Sol r1 blocker #3) — a CONTENT GIF animates ONLY when
 * (its §3 box is final — first valid native pair landed) AND (its real bounds intersect the
 * window — prefetched/off-viewport items do NOT animate) AND (the lifecycle is RESUMED). The gate
 * drives the [Animatable] result via start/stop — a non-animatable image is untouched, and
 * smileys/cc keep their historical behaviour (#175/#256: the gate only wraps content images).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostGifAnimationGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val gifUrl = "https://rehost.diberie.com/Picture/Get/f/anim.gif"

    private class FakeAnimatedDrawable : Drawable(), Animatable {
        var running = false
            private set
        var starts = 0
            private set
        var stops = 0
            private set

        override fun start() {
            running = true
            starts++
        }

        override fun stop() {
            running = false
            stops++
        }

        override fun isRunning(): Boolean = running
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = 400
        override fun getIntrinsicHeight(): Int = 300
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader(drawable: FakeAnimatedDrawable) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(gifUrl, drawable.asImage())
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun measuredCache(): DefaultIntrinsicMediaSizeCache =
        DefaultIntrinsicMediaSizeCache().apply { putSuccess(gifUrl, IntSize(400, 300)) }

    private fun setGifPost(
        cache: IntrinsicMediaSizeCache = measuredCache(),
        topSpacerDp: Int = 0,
        lifecycleOwner: LifecycleOwner? = null,
    ) {
        composeTestRule.setContent {
            val content = @androidx.compose.runtime.Composable {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                        Column {
                            if (topSpacerDp > 0) Spacer(Modifier.height(topSpacerDp.dp))
                            PostRenderer(
                                content = PostContent(
                                    blocks = listOf(
                                        PostBlock.Paragraph(
                                            inlines = listOf(
                                                PostInline.Text("gif "),
                                                PostInline.InlineImage(url = gifUrl, description = "anim"),
                                                PostInline.Text(" !"),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            if (lifecycleOwner != null) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) { content() }
            } else {
                content()
            }
        }
    }

    @Test
    fun `a measured visible resumed content gif animates`() {
        val drawable = FakeAnimatedDrawable()
        installLoader(drawable)
        setGifPost()
        composeTestRule.waitForIdle()
        assertTrue("the gate must start a measured, visible, resumed gif", drawable.running)
    }

    @Test
    fun `a cold content gif does not animate before its first native pair`() {
        val drawable = FakeAnimatedDrawable()
        installLoader(drawable)
        // Failure-seeded cache: the measurement never lands, the box stays the cold slot.
        val cache = DefaultIntrinsicMediaSizeCache().apply {
            putFailureIfEpoch(gifUrl, System.currentTimeMillis(), failureEpoch())
        }
        setGifPost(cache = cache)
        composeTestRule.waitForIdle()
        assertFalse("a gif without a final box must not animate", drawable.running)
    }

    @Test
    fun `a content gif composed OUT of the window does not animate`() {
        val drawable = FakeAnimatedDrawable()
        installLoader(drawable)
        // A 2000 dp spacer pushes the paragraph far below the 780 dp window: composed, laid out,
        // but its window bounds are empty — the prefetch shape.
        setGifPost(topSpacerDp = 2000)
        composeTestRule.waitForIdle()
        assertFalse("an off-window gif must not animate", drawable.running)
    }

    @Test
    fun `a VISIBLE animating gif stops when pushed out of the window`() {
        // Mini-gate P4 (Sol r1): the off-window test must prove the TRANSITION — animated while
        // visible, then an EFFECTIVE stop() once the bounds leave the window (not just "already
        // stopped off-screen").
        val drawable = FakeAnimatedDrawable()
        installLoader(drawable)
        val cache = measuredCache()
        val pushed = androidx.compose.runtime.mutableStateOf(false)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    Column {
                        if (pushed.value) Spacer(Modifier.height(2000.dp))
                        PostRenderer(
                            content = PostContent(
                                blocks = listOf(
                                    PostBlock.Paragraph(
                                        inlines = listOf(
                                            PostInline.InlineImage(url = gifUrl, description = "anim"),
                                            PostInline.Text(" gif"),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("the gif must animate while visible", drawable.running)
        val startsWhileVisible = drawable.starts
        assertTrue("at least one start while visible", startsWhileVisible >= 1)

        composeTestRule.runOnUiThread { pushed.value = true }
        composeTestRule.waitForIdle()
        assertFalse("the gif must STOP once pushed out of the window", drawable.running)
        assertTrue("an effective stop() must have fired on exit", drawable.stops >= 1)
    }

    @Test
    fun `a content gif does not animate below RESUMED and starts on resume`() {
        val drawable = FakeAnimatedDrawable()
        installLoader(drawable)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        setGifPost(lifecycleOwner = owner)
        composeTestRule.waitForIdle()
        assertFalse("a gif must not animate below RESUMED", drawable.running)

        owner.registry.currentState = Lifecycle.State.RESUMED
        composeTestRule.waitForIdle()
        assertTrue("the gif must start once the lifecycle reaches RESUMED", drawable.running)
    }
}
