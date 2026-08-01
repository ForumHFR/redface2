package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import fr.forumhfr.redface2.core.ui.R
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
 * #960 P3 — the §6 error slot + UNIVERSAL manual retry (contrat v1.5 §6 « painter KO : état
 * erreur DANS le slot réservé + retry manuel ; retry → nouvelle génération »):
 *
 *  - the retry is per-URL and strictly scoped (cadrage Sol r3: « retry manuel = LA seule URL du
 *    slot tapé ») — tapping one dead slot never re-attempts the other dead url of the same post;
 *  - the action is UNIVERSAL: it does not depend on the §5 host capability
 *    (LocalPostImageActions) — with the #960 per-URL ledger a retry is mechanically effective in
 *    EVERY host (annexe a11y « action annoncée seulement où effective » — the effectiveness now
 *    holds everywhere), inverting the Lot 2 pin « no retry action is ever announced »;
 *  - a11y: the error slot carries the localized error description and a Role.Button click
 *    action labelled « Réessayer » (annexe a11y, États §6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererErrorRetryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deadA = "https://images.example.org/dead-a.jpg"
    private val deadB = "https://images.example.org/dead-b.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private val requestedUrls = CopyOnWriteArrayList<String>()

    @Volatile
    private var serveDead = false

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(deadA, ColorImage(0xFF1565C0.toInt(), width = 320, height = 240))
            .intercept(deadB, ColorImage(0xFF2E7D32.toInt(), width = 320, height = 240))
            .build()
        val gate = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val url = chain.request.data as? String
                url?.let(requestedUrls::add)
                if (!serveDead && (url == deadA || url == deadB)) {
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

    /** Host stays NULL (no LocalPostImageActions): the retry must not depend on the capability. */
    private fun setContent(ledger: MediaAttemptLedger, blocks: List<PostBlock>) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(
                        LocalIntrinsicMediaSizeCache provides DefaultIntrinsicMediaSizeCache(),
                        LocalMediaAttemptLedger provides ledger,
                    ) {
                        PostRenderer(content = PostContent(blocks = blocks))
                    }
                }
            }
        }
    }

    private fun MediaAttemptLedger.painterFailed(url: String): Boolean =
        isFailedFresh(url, MediaAttemptKind.PAINTER, System.currentTimeMillis())

    private fun errorText(alt: String): String =
        appContext.getString(R.string.post_image_error_with_alt, alt)

    private val retryLabelled = SemanticsMatcher("has a retry-labelled click action") { node ->
        val click = node.config.getOrElseNullable(SemanticsActions.OnClick) { null }
        click?.label?.contains("Réessayer", ignoreCase = true) == true
    }

    @Test
    fun `tapping the inline error slot retries only the tapped url`() {
        installLoader()
        val ledger = MediaAttemptLedger()
        setContent(
            ledger,
            listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text("a "),
                        PostInline.InlineImage(url = deadA, description = "morte-a"),
                        PostInline.Text(" b "),
                        PostInline.InlineImage(url = deadB, description = "morte-b"),
                    ),
                ),
            ),
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.painterFailed(deadA) && ledger.painterFailed(deadB)
        }
        composeTestRule.waitForIdle()
        val bBefore = requestCount(deadB)

        // The host recovers; the reader taps A's error slot — B must stay untouched.
        serveDead = true
        composeTestRule.onNodeWithContentDescription(errorText("morte-a")).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.hasSucceeded(deadA, MediaAttemptKind.PAINTER)
        }
        composeTestRule.waitForIdle()
        assertTrue("B stays failed until ITS OWN retry", ledger.painterFailed(deadB))
        assertEquals("the retry is scoped to the tapped url", bBefore, requestCount(deadB))
    }

    @Test
    fun `tapping the block error slot recovers the image`() {
        installLoader()
        val ledger = MediaAttemptLedger()
        setContent(ledger, listOf(PostBlock.Image(url = deadA, description = "morte-bloc")))
        composeTestRule.waitUntil(timeoutMillis = 5_000) { ledger.painterFailed(deadA) }
        composeTestRule.waitForIdle()

        serveDead = true
        composeTestRule.onAllNodes(retryLabelled).assertCountEquals(1)
        composeTestRule.onAllNodes(retryLabelled)[0].performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.hasSucceeded(deadA, MediaAttemptKind.PAINTER)
        }
        composeTestRule.onNodeWithContentDescription("morte-bloc").assertExists()
    }

    @Test
    fun `the error slots announce the retry action on a NULL host - universal`() {
        // Inversion of the Lot 2 pin « no retry action is ever announced »: with the per-URL
        // ledger the retry is mechanically effective in every host, so the action is announced
        // everywhere — including hosts without any §5 image capability (MP, preview, signature).
        installLoader()
        val ledger = MediaAttemptLedger()
        setContent(
            ledger,
            listOf(
                PostBlock.Image(url = deadA, description = "morte"),
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text("x "),
                        PostInline.InlineImage(url = deadB, description = "morte aussi"),
                    ),
                ),
            ),
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ledger.painterFailed(deadA) && ledger.painterFailed(deadB)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(retryLabelled).assertCountEquals(2)
    }
}
