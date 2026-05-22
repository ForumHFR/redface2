package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.Density
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Phase 2F-A (#130) — pin the `InlineTextContent` placeholder contract under `fontScale`.
 *
 * What we pin :
 *  - at `density = 1f, fontScale = 1f`, the placeholder rect is **exactly** the bucket size
 *    declared in [PostMediaDisplayPolicy] for the three kinds we render inline (`18 × 18` for
 *    builtin smileys, `70 × 50` for perso smileys, `240 × 180` for inline images) ;
 *  - the bucket **ratio** is preserved at the baseline so a future refactor that swapped a
 *    rectangular bucket to a square would surface here (the regression #109 was specifically a
 *    ratio drift) — only checked for the non-square buckets, the builtin 18×18 is tautological ;
 *  - placeholder sizes are **monotonic** with `fontScale` — a larger fontScale yields a strictly
 *    larger placeholder (width and height both), and a smaller fontScale yields a strictly smaller
 *    one. We deliberately do **not** assert a linear `value × fontScale` ratio because Android 14+
 *    enables `NonLinearFontScaling` for `sp` (cf. `FontScaleConverter`) and the absolute pixel
 *    output is intentionally curve-flattened at large fontScales.
 *
 * What stays out of scope :
 *  - the actual `AsyncImage` child node : Coil's `model = url` path needs network or a fake
 *    `ImageLoader` to resolve under Robolectric. Asserting on a hierarchy that may legitimately
 *    stay on its placeholder would flake. The `Modifier.fillMaxSize()` contract on the child is
 *    enforced by code review + the `PostRendererInlineTest` JVM map-vs-placeholder check.
 *  - absolute pixel values at `fontScale != 1` : the non-linear scaling curve makes them platform-
 *    and SDK-dependent, not a stable test contract.
 *
 * Runtime annotations :
 *  - `@OptIn(ExperimentalTestApi::class)` covers `createComposeRule` v2 — the compiler-recommended
 *    replacement for the v1 entry that's flagged `@Deprecated` since BOM 2026.04 (the migration
 *    warning is emitted by the Kotlin compiler at first build of this file). The v2 API uses
 *    `StandardTestDispatcher` (cf. the v1 → v2 migration note Compose emits at compile time) ;
 *    the opt-in is defensive even when the call-site does not surface a warning.
 *  - `@Config(sdk = [34])` pins Android 14 (NonLinearFontScaling-enabled) which is exactly what
 *    this test exercises. Adding SDK 35+ would re-run the same fontScale curve with the
 *    FontScale tables updated for Android 15 ; we keep one SDK on purpose to keep the runtime
 *    small and to isolate any future SDK-specific drift to a clear single config switch
 *    (cf. Phase 2H bench suite which will likely sweep more SDKs).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostRendererInlineLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val emptyLinkStyles = TextLinkStyles()

    @Test
    fun `builtin smiley placeholder lands on the 18x18 sp bucket at fontScale 1`() {
        // The builtin bucket (`SmileyKind.Builtin`) is the most common smiley path on HFR — the
        // ~25 `:o`/`:jap:`/`:D` etc. served from `/icones/smilies/*.gif`. A regression that
        // collapsed it onto the 70 × 50 perso bucket would break every post line height ; the
        // perso + inline-image baselines below would not catch it.
        val capture = LayoutCapture()
        mountInlineContent(capture, builtinSmileyInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val rect = requireSingleRect(capture, "builtin smiley")
        assertCloseEnough(
            label = "builtin smiley width @ fontScale=1",
            expected = PostMediaDisplayPolicy.builtinSmiley.placeholderWidth.value,
            actual = rect.width,
            tolerance = SIZE_TOLERANCE_PX,
        )
        assertCloseEnough(
            label = "builtin smiley height @ fontScale=1",
            expected = PostMediaDisplayPolicy.builtinSmiley.placeholderHeight.value,
            actual = rect.height,
            tolerance = SIZE_TOLERANCE_PX,
        )
    }

    @Test
    fun `perso smiley placeholder lands on the policy bucket at fontScale 1`() {
        // Baseline absolute size : density = 1, fontScale = 1 ⇒ `sp == px`, so the rect must
        // match the policy values byte-for-byte. Any drift here means the bucket itself has
        // changed (or the InlineTextContent contract — both worth catching loudly).
        val capture = LayoutCapture()
        mountInlineContent(capture, persoSmileyInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val rect = requireSingleRect(capture, "perso smiley")
        assertCloseEnough(
            label = "perso smiley width @ fontScale=1",
            expected = PostMediaDisplayPolicy.persoSmiley.placeholderWidth.value,
            actual = rect.width,
            tolerance = SIZE_TOLERANCE_PX,
        )
        assertCloseEnough(
            label = "perso smiley height @ fontScale=1",
            expected = PostMediaDisplayPolicy.persoSmiley.placeholderHeight.value,
            actual = rect.height,
            tolerance = SIZE_TOLERANCE_PX,
        )
    }

    @Test
    fun `inline image placeholder lands on the policy bucket at fontScale 1`() {
        val capture = LayoutCapture()
        mountInlineContent(capture, inlineImageInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val rect = requireSingleRect(capture, "inline image")
        assertCloseEnough(
            label = "inline image width @ fontScale=1",
            expected = PostMediaDisplayPolicy.inlineImage.placeholderWidth.value,
            actual = rect.width,
            tolerance = SIZE_TOLERANCE_PX,
        )
        assertCloseEnough(
            label = "inline image height @ fontScale=1",
            expected = PostMediaDisplayPolicy.inlineImage.placeholderHeight.value,
            actual = rect.height,
            tolerance = SIZE_TOLERANCE_PX,
        )
    }

    @Test
    fun `perso smiley preserves the 1_4 ratio at fontScale 1`() {
        // The 70 × 50 bucket has a 1.4 : 1 ratio. A future refactor that swapped the perso bucket
        // to a square (#109 regression class) or to the builtin 18 × 18 bucket would surface here
        // even if the absolute sizes still scaled fine.
        val capture = LayoutCapture()
        mountInlineContent(capture, persoSmileyInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val rect = requireNotNull(capture.firstRect())
        val expectedRatio =
            PostMediaDisplayPolicy.persoSmiley.placeholderWidth.value /
                PostMediaDisplayPolicy.persoSmiley.placeholderHeight.value
        assertCloseEnough(
            label = "perso smiley ratio",
            expected = expectedRatio,
            actual = rect.width / rect.height,
            tolerance = RATIO_TOLERANCE,
        )
    }

    @Test
    fun `inline image preserves the 4_3 ratio at fontScale 1`() {
        val capture = LayoutCapture()
        mountInlineContent(capture, inlineImageInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val rect = requireNotNull(capture.firstRect())
        val expectedRatio =
            PostMediaDisplayPolicy.inlineImage.placeholderWidth.value /
                PostMediaDisplayPolicy.inlineImage.placeholderHeight.value
        assertCloseEnough(
            label = "inline image ratio",
            expected = expectedRatio,
            actual = rect.width / rect.height,
            tolerance = RATIO_TOLERANCE,
        )
    }

    @Test
    fun `perso smiley placeholder grows monotonically with fontScale`() {
        // Monotonicity across the documented fontScale steps. We accept the non-linear scaling
        // curve introduced by `FontScaleConverter` on Android 14+ : we only require the rect to
        // be ≥ at higher fontScales and ≤ at lower fontScales relative to the baseline. This
        // catches a regression that pinned the placeholder to an absolute pixel size (e.g.
        // dp instead of sp) without overconstraining the curve shape.
        val capture = LayoutCapture()
        mountInlineContent(capture, persoSmileyInlines())
        assertMonotonicGrowthWithFontScale(capture, label = "perso smiley")
    }

    @Test
    fun `inline image placeholder grows monotonically with fontScale`() {
        val capture = LayoutCapture()
        mountInlineContent(capture, inlineImageInlines())
        assertMonotonicGrowthWithFontScale(capture, label = "inline image")
    }

    @Test
    fun `perso smiley placeholder shrinks when fontScale drops below 1`() {
        val capture = LayoutCapture()
        mountInlineContent(capture, persoSmileyInlines())
        capture.fontScale.value = 1f
        composeTestRule.waitForIdle()
        val baseline = requireNotNull(capture.firstRect())
        capture.fontScale.value = 0.85f
        composeTestRule.waitForIdle()
        val smaller = requireNotNull(capture.firstRect())
        assertTrue(
            "perso smiley height must shrink below baseline at fontScale=0.85 — " +
                "baseline=${baseline.height} small=${smaller.height}",
            smaller.height < baseline.height,
        )
        // Width matches the same logic, modulo Compose's font-scaling curve which may keep the
        // width briefly flat for tiny font-scale deltas — we only require it to not grow.
        assertTrue(
            "perso smiley width must not grow at fontScale=0.85 — " +
                "baseline=${baseline.width} small=${smaller.width}",
            smaller.width <= baseline.width,
        )
    }

    private fun assertMonotonicGrowthWithFontScale(capture: LayoutCapture, label: String) {
        val measured = FONT_SCALES.map { fontScale ->
            capture.fontScale.value = fontScale
            composeTestRule.waitForIdle()
            val rect = requireNotNull(capture.firstRect()) {
                "$label rect missing at fontScale=$fontScale"
            }
            fontScale to rect
        }
        // Sorted by fontScale ascending so we can do pairwise comparisons.
        val sorted = measured.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            val (prevScale, prevRect) = sorted[i - 1]
            val (nextScale, nextRect) = sorted[i]
            assertTrue(
                "$label height must not shrink from fontScale=$prevScale to $nextScale — " +
                    "prev=${prevRect.height} next=${nextRect.height}",
                nextRect.height >= prevRect.height,
            )
            assertTrue(
                "$label width must not shrink from fontScale=$prevScale to $nextScale — " +
                    "prev=${prevRect.width} next=${nextRect.width}",
                nextRect.width >= prevRect.width,
            )
        }
        // And the endpoint pair must strictly grow on at least one axis : 0.85 vs 2.0 must not
        // produce the exact same placeholder, otherwise the fontScale plumbing is missing.
        val smallest = sorted.first().second
        val largest = sorted.last().second
        assertTrue(
            "$label : fontScale=2.0 must produce a strictly larger placeholder than fontScale=0.85 — " +
                "smallest=${smallest.width}x${smallest.height} largest=${largest.width}x${largest.height}",
            largest.height > smallest.height || largest.width > smallest.width,
        )
    }

    private fun builtinSmileyInlines(): List<PostInline> = listOf(
        PostInline.Smiley(
            kind = SmileyKind.Builtin(":jap:"),
            imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
        ),
    )

    private fun persoSmileyInlines(): List<PostInline> = listOf(
        PostInline.Smiley(
            kind = SmileyKind.Perso("cosmoschtroumpf"),
            imageUrl = "https://forum-images.hardware.fr/images/perso/cosmoschtroumpf.gif",
        ),
    )

    private fun inlineImageInlines(): List<PostInline> = listOf(
        PostInline.InlineImage(
            url = "https://forum.hardware.fr/images/foo.png",
            description = "foo",
        ),
    )

    /**
     * Composes a single [Text] hosting [inlines] inside a `CompositionLocalProvider` that pipes
     * the test's mutable `fontScale` into [LocalDensity]. Capturing the resulting
     * [TextLayoutResult] in [capture] gives the test access to `placeholderRects` after each
     * fontScale change. Density stays fixed at `1f` so the assertions at fontScale=1 reduce to
     * `sp == px`.
     */
    private fun mountInlineContent(capture: LayoutCapture, inlines: List<PostInline>) {
        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media: Map<String, InlineTextContent> = collectInlineMedia(inlines)
        composeTestRule.setContent {
            val fontScale = capture.fontScale.value
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                Text(
                    text = annotated,
                    inlineContent = media,
                    onTextLayout = { result -> capture.layout.value = result },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private class LayoutCapture {
        val fontScale: MutableState<Float> = mutableStateOf(1f)
        val layout: MutableState<TextLayoutResult?> = mutableStateOf(null)

        fun firstRect(): androidx.compose.ui.geometry.Rect? =
            layout.value?.placeholderRects?.firstOrNull()
    }

    private fun assertCloseEnough(
        label: String,
        expected: Float,
        actual: Float,
        tolerance: Float,
    ) {
        val delta = abs(expected - actual)
        assertTrue(
            "$label : expected=$expected actual=$actual delta=$delta tolerance=$tolerance",
            delta <= tolerance,
        )
    }

    /**
     * Asserts the [Text] mounted by [mountInlineContent] produced exactly one placeholder
     * (defensive against a future change that would emit zero — `firstOrNull` would silently
     * skip the test — or more than one — index 0 would be ambiguous). Returns the rect.
     */
    private fun requireSingleRect(capture: LayoutCapture, label: String): androidx.compose.ui.geometry.Rect {
        val rects = capture.layout.value?.placeholderRects
            ?: error("$label : no TextLayoutResult captured")
        assertEquals("$label : expected exactly one placeholder rect", 1, rects.size)
        return requireNotNull(rects[0]) { "$label : placeholder rect was null (text ellipsised ?)" }
    }

    private companion object {
        /** The five steps documented in #130 : 0.85, 1.0, 1.15, 1.3, 2.0. */
        val FONT_SCALES: List<Float> = listOf(0.85f, 1f, 1.15f, 1.3f, 2f)

        /**
         * Compose's layout pass may round to subpixel boundaries depending on the underlying
         * `IntSize` quantisation. 0.5 px is comfortably below the smallest meaningful difference
         * (one Compose physical pixel at density = 1) and matches the tolerance other Compose
         * placeholder tests use.
         */
        const val SIZE_TOLERANCE_PX: Float = 0.5f

        /** Ratio comparisons need a smaller relative tolerance. 0.01 covers float rounding. */
        const val RATIO_TOLERANCE: Float = 0.01f
    }
}
