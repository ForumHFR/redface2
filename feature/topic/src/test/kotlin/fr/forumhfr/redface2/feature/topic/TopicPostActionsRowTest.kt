package fr.forumhfr.redface2.feature.topic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * #882 P1 — the post footer's ACTUAL geometry contract:
 *
 *  1. the actions row (Modifier / « + Citer » / Citer) is EXACTLY the 48 dp M3 touch target at the
 *     default fontScale — no vertical margins stacked around it — under BOTH density presets
 *     (Comfort and Compact: the dropped m.postSpacing/m.cardBodyBottom were preset-dependent, the
 *     48 dp row is not);
 *  2. tapping « + Citer » moves NOTHING — the dynamic « Ajouté à la citation » pill used to push
 *     the whole content down by its own height on every selection; the layout shift WAS the bug,
 *     so the pin is an exact 0 px position delta, with and without the stable « cité N fois » pill;
 *  3. the three actions each keep a ≥ 48 dp touch target and never overlap;
 *  4. the 48 dp guard is a MINIMUM, not a fixed height: a large fontScale grows the row and the
 *     labels stay unclipped inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPostActionsRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // -- 1. Row height = the 48 dp touch target, parameterised over the density presets ------------

    @Test
    fun `actions row is exactly 48dp under the Comfort preset at default fontScale`() {
        assertActionsRowIs48dp(DisplayDensity.COMFORT)
    }

    @Test
    fun `actions row is exactly 48dp under the Compact preset at default fontScale`() {
        assertActionsRowIs48dp(DisplayDensity.COMPACT)
    }

    private fun assertActionsRowIs48dp(density: DisplayDensity) {
        mountCard(density = density)
        composeTestRule.onNodeWithTag(TOPIC_POST_ACTIONS_ROW_TAG)
            .assertHeightIsEqualTo(48.dp)
    }

    // -- 2. Zero layout shift on « + Citer » -------------------------------------------------------

    @Test
    fun `tapping plus Citer shifts the content by exactly 0px`() {
        mountCard()
        val bodyBefore = composeTestRule.onNodeWithText(BODY_TEXT).getUnclippedBoundsInRoot()
        val rowBefore = composeTestRule.onNodeWithTag(TOPIC_POST_ACTIONS_ROW_TAG)
            .getUnclippedBoundsInRoot()

        composeTestRule.onNodeWithContentDescription(ADD_DESC).performClick()
        composeTestRule.waitForIdle()

        // Sanity: the selection DID land (button flipped) — we compare a selected card, not a no-op.
        composeTestRule.onNodeWithText(REMOVE_LABEL).assertIsDisplayed()
        // The shift was THE bug (#882): the pill used to push everything below it by its own
        // height on every tap. DpRect equality = an exact 0 px delta on every edge.
        assertEquals(
            "post body must not move when the post joins the multi-quote basket",
            bodyBefore,
            composeTestRule.onNodeWithText(BODY_TEXT).getUnclippedBoundsInRoot(),
        )
        assertEquals(
            "actions row must not move when the post joins the multi-quote basket",
            rowBefore,
            composeTestRule.onNodeWithTag(TOPIC_POST_ACTIONS_ROW_TAG).getUnclippedBoundsInRoot(),
        )
    }

    @Test
    fun `the stable cited pill survives selection and the content still does not move`() {
        mountCard(citedCount = 3)
        // The STABLE pill (#239 « cité N fois ») is rendered before any selection…
        composeTestRule.onNodeWithText(CITED_PILL_3).assertIsDisplayed()
        val bodyBefore = composeTestRule.onNodeWithText(BODY_TEXT).getUnclippedBoundsInRoot()

        composeTestRule.onNodeWithContentDescription(ADD_DESC).performClick()
        composeTestRule.waitForIdle()

        // …and is still there after: only the DYNAMIC basket pill was dropped by #882 P1.
        composeTestRule.onNodeWithText(CITED_PILL_3).assertIsDisplayed()
        composeTestRule.onNodeWithText(REMOVE_LABEL).assertIsDisplayed()
        assertEquals(
            "post body must not move even when the stable cited pill is present",
            bodyBefore,
            composeTestRule.onNodeWithText(BODY_TEXT).getUnclippedBoundsInRoot(),
        )
    }

    // -- 3. Touch targets: >= 48 dp each, no overlap -----------------------------------------------

    @Test
    fun `the three actions keep a 48dp touch target without overlapping`() {
        mountCard()
        val edit = composeTestRule.onNodeWithText(EDIT_LABEL)
            .fetchSemanticsNode().touchBoundsInRoot
        val toggle = composeTestRule.onNodeWithContentDescription(ADD_DESC)
            .fetchSemanticsNode().touchBoundsInRoot
        val quote = composeTestRule.onNodeWithText(QUOTE_LABEL)
            .fetchSemanticsNode().touchBoundsInRoot

        // NB: no destructured lambda parameters here — `{ (label, bounds) -> … }` crashes the
        // K2 frontend embedded in this AGP's lint (« Unknown annotation target ( » in
        // PsiRawFirBuilder), failing :feature:topic:lintAnalyzeDebugUnitTest. Plain Pair access
        // until the toolchain moves past it.
        val targets = listOf("Modifier" to edit, "+ Citer" to toggle, "Citer" to quote)
        with(composeTestRule.density) {
            targets.forEach { target ->
                assertTrue(
                    "« ${target.first} » touch height ${target.second.height.toDp()} must be >= 48dp",
                    target.second.height.toDp().value >= MIN_TARGET_DP - DP_EPSILON,
                )
                assertTrue(
                    "« ${target.first} » touch width ${target.second.width.toDp()} must be >= 48dp",
                    target.second.width.toDp().value >= MIN_TARGET_DP - DP_EPSILON,
                )
            }
        }
        // Pairwise horizontal disjointness — they share the row, so an overlap could only be
        // horizontal (an overlapping 48 dp expansion would make taps ambiguous).
        targets.forEachIndexed { i, a ->
            targets.drop(i + 1).forEach { b ->
                assertTrue(
                    "« ${a.first} » (${a.second}) and « ${b.first} » (${b.second}) must not overlap",
                    a.second.right <= b.second.left + PX_EPSILON ||
                        b.second.right <= a.second.left + PX_EPSILON,
                )
            }
        }
    }

    // -- 4. Large fontScale: the row GROWS (minimum, never a fixed height) -------------------------

    @Test
    fun `a large fontScale grows the row instead of clipping the labels`() {
        val fontScale = mutableStateOf(1f)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = fontScale.value),
                ) {
                    SelectableCard()
                }
            }
        }
        val heightAt1 = composeTestRule.onNodeWithTag(TOPIC_POST_ACTIONS_ROW_TAG)
            .fetchSemanticsNode().boundsInRoot.height

        fontScale.value = 2f
        composeTestRule.waitForIdle()

        val rowAt2 = composeTestRule.onNodeWithTag(TOPIC_POST_ACTIONS_ROW_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "row must grow under fontScale 2 (was ${heightAt1}px, now ${rowAt2.height}px)",
            rowAt2.height > heightAt1,
        )
        // No clip: the grown label sits fully inside the grown row.
        val label = composeTestRule.onNodeWithText(QUOTE_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "label ($label) must not clip above the row ($rowAt2)",
            label.top >= rowAt2.top - PX_EPSILON,
        )
        assertTrue(
            "label ($label) must not clip below the row ($rowAt2)",
            label.bottom <= rowAt2.bottom + PX_EPSILON,
        )
    }

    // -- Fixture ------------------------------------------------------------------------------------

    private fun mountCard(
        density: DisplayDensity = DisplayDensity.COMFORT,
        citedCount: Int = 0,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                amoledTheme = false,
                reading = ReadingDisplaySettings(density = density),
                dynamicColor = false,
            ) {
                SelectableCard(citedCount = citedCount)
            }
        }
    }

    /** The card under test, with a REAL selection round-trip wired on the « + » toggle. */
    @Composable
    private fun SelectableCard(citedCount: Int = 0) {
        var selected by remember { mutableStateOf(false) }
        TopicPostCard(
            post = samplePost(),
            citedCount = citedCount,
            onQuote = {},
            onEdit = {},
            multiQuoteSelected = selected,
            onToggleMultiQuote = { selected = !selected },
        )
    }

    private fun samplePost(): Post = Post(
        numreponse = 16244,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(BODY_TEXT)))),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = 1,
        profileId = null,
    )

    private companion object {
        const val BODY_TEXT = "corps du post — repère de position"
        const val EDIT_LABEL = "Modifier"
        const val QUOTE_LABEL = "Citer"
        const val REMOVE_LABEL = "✓ Cité"
        const val ADD_DESC = "Ajouter à la citation multiple"
        const val CITED_PILL_3 = "cité 3 fois"
        const val MIN_TARGET_DP = 48f
        const val DP_EPSILON = 0.05f
        const val PX_EPSILON = 0.5f
    }
}
