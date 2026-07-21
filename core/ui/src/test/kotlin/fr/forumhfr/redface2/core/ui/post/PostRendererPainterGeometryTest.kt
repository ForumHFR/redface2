package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #960 P2 — the G2 protocol (contrat v1.5 §6, « probe KO, painter OK ») wired into the REAL
 * pipeline:
 *
 *  - the painter's ORIENTED image dimensions (`coil3.Image.width/height`, the normative source
 *    §3) produce THE unique box correction from the cold slot when the probe could not — and
 *    settle the PROBE axis too (the measurement need is met), so the URL is stable forever:
 *    no TTL advancement, no replayed probe, no re-decoded painter;
 *  - a painter success WITHOUT usable geometry (no intrinsic dimensions) keeps the cold box and
 *    leaves the probe retryable (C1) — never the painter;
 *  - the FIRST valid pair keeps the authority: a later disagreeing pair is never applied
 *    (`putSuccessIfAbsent`), pinned at the cache level in [IntrinsicMediaSizeCacheTest].
 *
 * The probe is killed SELECTIVELY: it is the only request carrying [ProbeMetadataDecoder.Factory],
 * so the loader interceptor fails those and serves the painter normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererPainterGeometryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val g2Url = "https://images.example.org/probe-dead-painter-alive.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private val requestedUrls = CopyOnWriteArrayList<String>()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader(painterImage: ColorImage) {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(g2Url, painterImage)
            .build()
        val probeKiller = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                (chain.request.data as? String)?.let(requestedUrls::add)
                if (chain.request.decoderFactory is ProbeMetadataDecoder.Factory) {
                    return ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("probe refused by host"),
                    )
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(probeKiller)
                add(engine)
            }.build(),
        )
    }

    private fun setContent(ledger: MediaAttemptLedger, cache: IntrinsicMediaSizeCache) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(
                        LocalIntrinsicMediaSizeCache provides cache,
                        LocalMediaAttemptLedger provides ledger,
                    ) {
                        PostRenderer(
                            content = PostContent(
                                blocks = listOf(
                                    PostBlock.Paragraph(
                                        inlines = listOf(
                                            PostInline.Text("regarde "),
                                            PostInline.InlineImage(url = g2Url, description = "photo"),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a probe-dead painter-alive url takes its box from the painter geometry - G2`() {
        installLoader(painterImage = ColorImage(0xFF6A1B9A.toInt(), width = 320, height = 240))
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        setContent(ledger, cache)

        // The painter geometry lands in the measurement cache (the unique correction §6)…
        composeTestRule.waitUntil(timeoutMillis = 5_000) { cache.get(g2Url) != null }
        assertEquals(IntSize(320, 240), cache.get(g2Url))
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {}

        // …the box grows from the 16 sp cold square to the §3 size…
        val grownHeight = composeTestRule.onNodeWithContentDescription("photo").getBoundsInRoot().height
        assertTrue("the G2 box must grow to the painter geometry (was $grownHeight)", grownHeight > 60.dp)

        // …BOTH axes are settled (the measurement need is met by the painter)…
        assertTrue(ledger.hasSucceeded(g2Url, MediaAttemptKind.PAINTER))
        assertTrue(
            "G2 must settle the probe axis too — the url becomes stable forever",
            ledger.hasSucceeded(g2Url, MediaAttemptKind.PROBE),
        )
        // …and the URL is stable: no TTL advancement can ever reopen it.
        val gen = ledger.generationOf(g2Url)
        assertEquals(gen, ledger.consultGeneration(g2Url, System.currentTimeMillis() + 120_000L))

        // Exactly: one failed probe + the cold painter decode (256 bucket) + THE unique §7
        // re-decode once G2 fixed the box (Lot 3 pin: cold→measured = exactly one new decode).
        // No retry storm beyond that.
        assertEquals(3, requestedUrls.count { it == g2Url })
    }

    @Test
    fun `a painter success without usable geometry keeps the cold box and the probe retryable`() {
        // ColorImage without explicit dimensions reports -1×-1 (unbounded) — a success carrying
        // no usable geometry (§6: « aucune dimension exploitable → boîte cold CONSERVÉE »).
        installLoader(painterImage = ColorImage(0xFF6A1B9A.toInt()))
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        setContent(ledger, cache)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.hasSucceeded(g2Url, MediaAttemptKind.PAINTER)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {}

        assertNull("no geometry may be deposited", cache.get(g2Url))
        assertFalse(
            "the probe axis must stay retryable (C1) — the measurement need is NOT met",
            ledger.hasSucceeded(g2Url, MediaAttemptKind.PROBE),
        )
        assertTrue(
            "the failed probe stays on record until its TTL",
            ledger.isFailedFresh(g2Url, MediaAttemptKind.PROBE, System.currentTimeMillis()),
        )
        val coldHeight = composeTestRule.onNodeWithContentDescription("photo").getBoundsInRoot().height
        assertTrue("the cold box must be preserved (was $coldHeight)", coldHeight <= 24.dp)
    }
}
