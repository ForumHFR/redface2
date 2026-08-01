package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #960 P4 — exotic formats through the REAL decode pipeline (no FakeImageLoaderEngine: it
 * intercepts before decoding and can never exercise a Decoder — Lot 3 lesson). The loader
 * mirrors the production roster (RedfaceApplication): Coil's built-in file fetcher + the real
 * [SvgDecoder]; sources are real temp files.
 *
 *  - SVG: the header-only probe CANNOT read SVG bounds by design (BitmapFactory) — the probe
 *    settles a failure and the painter's decoded geometry fixes the box through G2 (§6). This is
 *    the contractual path for every non-BitmapFactory format.
 *  - AVIF (C2, matrice assumée): this JVM runtime has no AVIF codec — a REAL AVIF payload (321
 *    bytes, ftypavif) lands in the retryable §6 error slot, never a crash. On devices WITH the
 *    platform codec (most API 31+) the same file renders normally — pinned at the P5 device
 *    bench (S10e API 31 = painter-KO stable, #962), not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererExoticFormatsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installRealLoader() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components { add(SvgDecoder.Factory()) }.build(),
        )
    }

    private fun setContent(ledger: MediaAttemptLedger, cache: IntrinsicMediaSizeCache, url: String) {
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
                                            PostInline.InlineImage(url = url, description = "exotique"),
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
    fun `a real svg takes its box through the G2 path`() {
        installRealLoader()
        val svg = File.createTempFile("exotic", ".svg").apply {
            writeText(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="60">""" +
                    """<rect width="100" height="60" fill="#1565C0"/></svg>""",
            )
            deleteOnExit()
        }
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        setContent(ledger, cache, svg.absolutePath)

        // The probe fails by design (BitmapFactory cannot read SVG bounds)…
        composeTestRule.waitUntil(timeoutMillis = 5_000) { cache.get(svg.absolutePath) != null }
        composeTestRule.waitForIdle()
        // …and the PAINTER geometry fixes the box (G2): both axes terminal, the pair keeps the
        // SVG's 5:3 ratio (the decoded size follows the §7 request, not the viewport 100×60 —
        // §3 accepts the painter pair as the first valid authority).
        assertTrue(ledger.hasSucceeded(svg.absolutePath, MediaAttemptKind.PAINTER))
        assertTrue(
            "G2 must settle the probe axis from the painter geometry",
            ledger.hasSucceeded(svg.absolutePath, MediaAttemptKind.PROBE),
        )
        val pair = cache.get(svg.absolutePath)!!
        assertTrue("a positive pair is deposited (was $pair)", pair.width > 0 && pair.height > 0)
        val ratio = pair.width.toFloat() / pair.height
        assertTrue(
            "the deposited pair must keep the SVG ratio 5:3 (was $pair)",
            ratio > 1.6f && ratio < 1.72f,
        )
    }

    @Test
    fun `an avif this runtime cannot decode lands in the retryable error slot`() {
        installRealLoader()
        val avif = File.createTempFile("exotic", ".avif").apply {
            writeBytes(Base64.decode(REAL_AVIF_BASE64, Base64.DEFAULT))
            deleteOnExit()
        }
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        setContent(ledger, cache, avif.absolutePath)

        // No AVIF codec in this runtime: probe AND painter settle failures — the §6 error slot
        // with its universal retry action, never a crash (C2: the matrix is assumed, the device
        // bench pins the decodable side).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.isFailedFresh(avif.absolutePath, MediaAttemptKind.PAINTER, System.currentTimeMillis())
        }
        composeTestRule.waitForIdle()
        assertNull("no geometry can exist without a decode", cache.get(avif.absolutePath))
        val retryLabelled = SemanticsMatcher("has a retry-labelled click action") { node ->
            val click = node.config.getOrElseNullable(SemanticsActions.OnClick) { null }
            click?.label?.contains("Réessayer", ignoreCase = true) == true
        }
        composeTestRule.onAllNodes(retryLabelled).assertCountEquals(1)
    }

    private companion object {
        /** A REAL 8×6 AVIF (ffmpeg libaom-av1, still picture, 321 bytes) — ftypavif/av01. */
        const val REAL_AVIF_BASE64 =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUEAAAD5bWV0YQAAAAAAAAAvaGRscgAAAAAAAAAAcGljdAAAAAAAAAAA" +
                "AAAAAFBpY3R1cmVIYW5kbGVyAAAAAA5waXRtAAAAAAABAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAASEAAAAgAAAAKGlpbmYA" +
                "AAAAAAEAAAAaaW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAAAAAIAAAABgAAABBw" +
                "aXhpAAAAAAMICAgAAAAMYXYxQ4EgAAAAAAATY29scm5jbHgAAgACAACAAAAAF2lwbWEAAAAAAAAAAQABBAECgwQAAAAobWRh" +
                "dAoIOAi9bQENACAyFBgAAABQAAAABfXvZN1zAeOaG2HY"
    }
}
