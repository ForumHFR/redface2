package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #960 (Lot 4, contrat v1.5 §6, cadrage Sol r3 — mini-gate P1) — the [MediaAttemptLedger] wired
 * into the REAL render pipeline. Three integration pins, counted at the loader (an interceptor
 * sees every request the pipeline actually fires — probe and painter alike):
 *
 *  1. one attempt per (URL, generation, axis): two on-screen occurrences of the same dead URL
 *     cost exactly ONE probe + ONE painter request — the loser observes the settled state
 *     through the snapshot map instead of re-firing (lock #2);
 *  2. a fresh failure is authoritative: a LATER occurrence of the same URL (new paragraph
 *     composed while the failure is fresh) fires ZERO new requests (§6 — no per-occurrence
 *     retry storm);
 *  3. `retryFailedUrls` is strictly scoped (lock #1): the dead URL re-attempts (one probe + one
 *     painter, then recovers) while the healthy URL of the SAME gesture is never re-requested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererMediaLedgerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deadUrl = "https://images.example.org/dead-photo.jpg"
    private val healthyUrl = "https://images.example.org/healthy-photo.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    /** Every url the loader was asked for (probe and painter alike), cumulative. */
    private val requestedUrls = CopyOnWriteArrayList<String>()

    /** Recovery toggle — NEVER swap the singleton loader mid-test: a fresh loader means a fresh
     * memory cache, and any recomposition would re-request the healthy url through it, polluting
     * the scoped-retry counting with a harness artefact. */
    @Volatile
    private var serveDead = false

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(deadUrl, ColorImage(0xFF1565C0.toInt(), width = 320, height = 240))
            .intercept(healthyUrl, ColorImage(0xFF2E7D32.toInt(), width = 320, height = 240))
            .build()
        val gate = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val url = chain.request.data as? String
                url?.let(requestedUrls::add)
                if (url == deadUrl && !serveDead) {
                    // The production failure mode: the host is down until the toggle flips.
                    return ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("dead host"),
                    )
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(gate)
                add(engine)
            }.build(),
        )
    }

    private fun requestCount(url: String): Int = requestedUrls.count { it == url }

    /** A paragraph whose TEXT sibling keeps [url] INLINE (no structural block promotion). */
    private fun inlineImageParagraph(url: String, prefix: String = "regarde ") = PostBlock.Paragraph(
        inlines = listOf(
            PostInline.Text(prefix),
            PostInline.InlineImage(url = url, description = "photo"),
        ),
    )

    private fun setContent(ledger: MediaAttemptLedger, cache: IntrinsicMediaSizeCache, blocks: () -> List<PostBlock>) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(
                        LocalIntrinsicMediaSizeCache provides cache,
                        LocalMediaAttemptLedger provides ledger,
                    ) {
                        PostRenderer(content = PostContent(blocks = blocks()))
                    }
                }
            }
        }
    }

    private fun MediaAttemptLedger.bothAxesFailedFresh(url: String): Boolean {
        val now = System.currentTimeMillis()
        return isFailedFresh(url, MediaAttemptKind.PROBE, now) &&
            isFailedFresh(url, MediaAttemptKind.PAINTER, now)
    }

    @Test
    fun `two occurrences of the same dead url attempt once per axis`() {
        installLoader()
        val ledger = MediaAttemptLedger()
        setContent(ledger, DefaultIntrinsicMediaSizeCache()) {
            listOf(
                inlineImageParagraph(deadUrl, prefix = "premier "),
                inlineImageParagraph(deadUrl, prefix = "second "),
            )
        }

        // Both axes settle their single attempt as failures…
        composeTestRule.waitUntil(timeoutMillis = 5_000) { ledger.bothAxesFailedFresh(deadUrl) }
        composeTestRule.waitForIdle()
        // …and the loader saw exactly ONE probe + ONE painter request despite two occurrences.
        assertEquals(
            "two occurrences must share one probe and one painter attempt",
            2,
            requestCount(deadUrl),
        )
    }

    @Test
    fun `a later occurrence of a fresh-failed url fires no new request`() {
        installLoader()
        val ledger = MediaAttemptLedger()
        var showSecond by mutableStateOf(false)
        setContent(ledger, DefaultIntrinsicMediaSizeCache()) {
            buildList {
                add(inlineImageParagraph(deadUrl, prefix = "premier "))
                if (showSecond) add(inlineImageParagraph(deadUrl, prefix = "second "))
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { ledger.bothAxesFailedFresh(deadUrl) }
        composeTestRule.waitForIdle()
        val before = requestCount(deadUrl)

        // A NEW paragraph with the same URL composes while the failure is fresh (§6): its probe
        // guard and painter gate must both observe the ledger — zero new requests.
        composeTestRule.runOnIdle { showSecond = true }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {}
        assertEquals("a fresh failure must gate every new occurrence", before, requestCount(deadUrl))
    }

    @Test
    fun `retryFailedUrls retries the dead url and never touches the healthy one`() {
        installLoader()
        val ledger = MediaAttemptLedger()
        setContent(ledger, DefaultIntrinsicMediaSizeCache()) {
            listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text("morte "),
                        PostInline.InlineImage(url = deadUrl, description = "morte"),
                        PostInline.Text(" saine "),
                        PostInline.InlineImage(url = healthyUrl, description = "saine"),
                    ),
                ),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.bothAxesFailedFresh(deadUrl) && ledger.hasSucceeded(healthyUrl, MediaAttemptKind.PAINTER)
        }
        composeTestRule.waitForIdle()
        val deadBefore = requestCount(deadUrl)
        val healthyBefore = requestCount(healthyUrl)

        // The outage recovers; the screen's refresh gesture retries ITS OWN failed urls only —
        // the healthy url is in the scope but healthy, so it must stay untouched (lock #1).
        serveDead = true
        composeTestRule.runOnIdle { ledger.retryFailedUrls(setOf(deadUrl, healthyUrl)) }
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.hasSucceeded(deadUrl, MediaAttemptKind.PAINTER) &&
                ledger.hasSucceeded(deadUrl, MediaAttemptKind.PROBE)
        }
        composeTestRule.waitForIdle()
        assertEquals(
            "the recovered url re-attempts exactly once per axis",
            deadBefore + 2,
            requestCount(deadUrl),
        )
        assertEquals(
            "the healthy url must never be re-requested by a scoped retry",
            healthyBefore,
            requestCount(healthyUrl),
        )
        assertTrue(ledger.hasSucceeded(deadUrl, MediaAttemptKind.PAINTER))
    }
}
