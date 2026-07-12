package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #813 — a ghost inline image (its first measurement failed: dead host, transient outage) must
 * become recoverable through the screen's EXPLICIT refresh path: `clearPostMediaMeasurementFailures()`
 * then a `mediaRefreshGeneration` bump — WITHOUT disposing the composition (the historical
 * work-around was « citer puis revenir », which disposed the whole topic composition).
 *
 * Two pins, matching the two ghost mechanisms:
 *  - the placeholder BOX grows from the 16 sp cold square to the measured size (the measure effect
 *    relaunched — it never did on refresh before, `inlines` being structurally equal);
 *  - the PAINTER actually re-attempts its load (what `key(generation)` buys) — proven by counting
 *    the loader requests for the URL, pixel capture being unreliable for Coil drawings under this
 *    harness. After the bump the URL must be requested at least twice more: the re-probe AND the
 *    recreated painter. Without `key()` only the probe re-fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererGhostImageRecoveryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ghostUrl = "https://images.example.org/ghost-photo.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    /** Every url the loader was asked for, across engine swaps (class-level, cumulative). */
    private val requestedUrls = CopyOnWriteArrayList<String>()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader(serveGhost: Boolean) {
        val builder = FakeImageLoaderEngine.Builder()
        if (serveGhost) {
            builder.intercept(ghostUrl, ColorImage(0xFF1565C0.toInt(), width = 320, height = 240))
        }
        // else: ghostUrl NOT intercepted → Coil error result = the production failure mode.
        val engine = builder.build()
        val counter = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                (chain.request.data as? String)?.let(requestedUrls::add)
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(counter)
                add(engine)
            }.build(),
        )
    }

    private fun ghostRequestCount(): Int = requestedUrls.count { it == ghostUrl }

    @Test
    fun `explicit refresh (clear + generation bump) recovers a ghost inline image in place`() {
        installLoader(serveGhost = false)
        val cache = DefaultIntrinsicMediaSizeCache()
        var generation by mutableIntStateOf(0)

        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                        PostRenderer(
                            content = PostContent(
                                blocks = listOf(
                                    PostBlock.Paragraph(
                                        inlines = listOf(
                                            // The text sibling keeps the image INLINE (no block
                                            // promotion) — the ghost symptom is inline-specific.
                                            PostInline.Text("regarde "),
                                            PostInline.InlineImage(url = ghostUrl, description = "photo"),
                                        ),
                                    ),
                                ),
                            ),
                            mediaRefreshGeneration = generation,
                        )
                    }
                }
            }
        }

        // Phase 1 — the probe fails and is memoized; the box stays the 16 sp cold square.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            cache.isFailureFresh(ghostUrl, System.currentTimeMillis())
        }
        val coldHeight = composeTestRule.onNodeWithContentDescription("photo").getBoundsInRoot().height
        assertTrue(
            "cold ghost box must stay around the one-line square (was $coldHeight)",
            coldHeight <= 24.dp,
        )

        // Phase 2 — the outage recovers, the user pulls to refresh: the screen clears the memoized
        // failures FIRST, then bumps the generation (the production order in TopicContent).
        installLoader(serveGhost = true)
        composeTestRule.waitForIdle()
        val requestsBeforeRefresh = ghostRequestCount()
        composeTestRule.runOnIdle {
            cache.clearFailures()
            generation++
        }
        // Apply the recomposition so the keyed measure effect actually relaunches — under
        // Robolectric, waitUntil's polling alone does not reliably drive that application.
        composeTestRule.waitForIdle()

        // Same composition (nothing was disposed) : the paragraph re-probes, the box grows.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            cache.get(ghostUrl) != null
        }
        composeTestRule.runOnIdle {} // let the size write recompose the paragraph
        val grownHeight = composeTestRule.onNodeWithContentDescription("photo").getBoundsInRoot().height
        assertTrue(
            "the ghost box must grow to the measured size after the refresh (was $grownHeight)",
            grownHeight > 60.dp,
        )

        // Painter proof (Sol gate r1) : the refresh must trigger BOTH the re-probe and a fresh
        // painter load — ≥ 2 new loader requests for the URL. Without key(generation) the stuck
        // error painter never re-requests and only the probe (+1) fires.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ghostRequestCount() >= requestsBeforeRefresh + 2
        }
    }
}
