package fr.forumhfr.redface2.feature.messages

import android.widget.Magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.CREATOR_PSEUDO_TEXT_TAG
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.core.ui.theme.DisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * #1042 — what [MessageCard] gains by adapting the shared reading card, pinned from the MP side:
 *
 *  - card geometry FOLLOWS the [LocalDisplayMetrics] preset (measured, not merely crash-free) —
 *    the MP density is no longer feature-fixed;
 *  - body state nested in `rememberSaveable` (an unfolded long quote) SURVIVES a density flip:
 *    the #946 guarantee that no capability change recreates the body subtree;
 *  - the same body state (quote + spoiler) survives the #1050 full-width flip;
 *  - the body is selectable, and that capability derives from NEITHER the density preset NOR the
 *    presence of a callback or full-width presentation — it is structurally constant (#946);
 *  - the profile tap reaches [MessageCard.onOpenProfile] from both the avatar and the gold creator
 *    pseudo, without adding a second TalkBack heading (the #884 exactly-one-heading contract, whose
 *    two MP pseudo variants live in [MessageCardShellSmokeTest]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// NoopShadowMagnifier: the touch-selection magnifier NPEs under Robolectric (its popup Surface is
// never created — `Magnifier$InternalPopupWindow.destroy` crashes on the first dismiss), so the
// platform widget is stubbed out. Selection itself is untouched: the toolbar observation stands.
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi", shadows = [NoopShadowMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageCardReadingParityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `body gutter follows the density preset`() {
        val metrics = mutableStateOf(BASE_METRICS)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDisplayMetrics provides metrics.value) {
                    Box(modifier = Modifier.testTag(CARD_TAG)) {
                        MessageCard(message = sampleMessage())
                    }
                }
            }
        }

        assertEquals(
            "the body gutter must equal the preset's cardBodyHorizontal",
            BASE_METRICS.cardBodyHorizontal.value,
            measuredBodyGutterDp(),
            DP_TOLERANCE,
        )

        compose.runOnIdle { metrics.value = WIDE_GUTTER_METRICS }

        assertEquals(
            "the body gutter must TRACK a preset change, not keep the mount-time value",
            WIDE_GUTTER_METRICS.cardBodyHorizontal.value,
            measuredBodyGutterDp(),
            DP_TOLERANCE,
        )
    }

    @Test
    fun `vertical rhythm follows the density preset`() {
        val metrics = mutableStateOf(BASE_METRICS)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDisplayMetrics provides metrics.value) {
                    Box(modifier = Modifier.testTag(CARD_TAG)) {
                        MessageCard(message = sampleMessage())
                    }
                }
            }
        }

        val baseHeight = measuredCardHeightDp()
        compose.runOnIdle { metrics.value = TALL_RHYTHM_METRICS }
        val tallHeight = measuredCardHeightDp()

        // MessageCard's vertical envelope from the preset: the zero-padding identity band leaves the
        // header's historical cardBodyTop inset untouched, while the shared body adds cardBodyTop as
        // the header↔body gap and cardBodyBottom below (footer-less card). The two presets share every
        // other metric, so the height delta still isolates exactly those three MP-owned paddings.
        val expectedDelta =
            2 * (TALL_RHYTHM_METRICS.cardBodyTop - BASE_METRICS.cardBodyTop).value +
                (TALL_RHYTHM_METRICS.cardBodyBottom - BASE_METRICS.cardBodyBottom).value
        assertEquals(
            "the card's vertical envelope must follow the preset's cardBodyTop/cardBodyBottom",
            expectedDelta,
            tallHeight - baseHeight,
            DP_TOLERANCE,
        )
    }

    @Test
    fun `an expanded long quote survives a density preset change`() {
        val metrics = mutableStateOf(BASE_METRICS)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalDisplayMetrics provides metrics.value,
                    LocalFoldLongQuotes provides true,
                ) {
                    Box(modifier = Modifier.testTag(CARD_TAG)) {
                        MessageCard(message = sampleMessage(content = longQuoteContent()))
                    }
                }
            }
        }

        // Unfold the long quote (the #332 fold toggles on tap; « Replier » proves it is open).
        compose.onNodeWithText("Déplier", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Replier", substring = true).assertExists()

        compose.runOnIdle { metrics.value = WIDE_GUTTER_METRICS }
        compose.waitForIdle()

        // The flip really landed (the marker paragraph moved to the new gutter). The marker sits
        // ABOVE the quote: below the expanded 30-line quote it would leave the root window, where
        // clipped semantics bounds read as Rect.Zero and the measurement would be meaningless.
        assertEquals(
            "the density flip must have taken effect",
            WIDE_GUTTER_METRICS.cardBodyHorizontal.value,
            measuredGutterDp(MARKER_TEXT),
            DP_TOLERANCE,
        )
        // …and the quote is STILL expanded: the #946 guarantee that a density change recomposes
        // the body in place instead of recreating it (which would drop rememberSaveable state).
        compose.onNodeWithText("Replier", substring = true).assertExists()
    }

    @Test
    fun `expanded quote and revealed spoiler survive a full width toggle`() {
        val fullWidth = mutableStateOf(false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalFoldLongQuotes provides true) {
                    MessageCard(
                        message = sampleMessage(content = statefulBodyContent()),
                        presentation = ReadingPostCardPresentation(flat = fullWidth.value),
                    )
                }
            }
        }

        compose.onNodeWithText("(afficher)").performClick()
        compose.onNodeWithText(SPOILER_TEXT).assertExists()
        compose.onNodeWithText("Déplier", substring = true).performClick()
        compose.onNodeWithText("Replier", substring = true).assertExists()

        compose.runOnIdle { fullWidth.value = true }
        compose.waitForIdle()

        // The divider proves the mode flip reached the shell; the two controls staying open prove
        // its stable Card/Column structure kept the body subtree and its rememberSaveable state.
        compose.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("(masquer)").assertExists()
        compose.onNodeWithText(SPOILER_TEXT).assertExists()
        compose.onNodeWithText("Replier", substring = true).assertExists()
    }

    @Test
    fun `body stays selectable after a full width toggle`() {
        val toolbar = RecordingTextContextMenuProvider()
        val fullWidth = mutableStateOf(false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    MessageCard(
                        message = sampleMessage(),
                        presentation = ReadingPostCardPresentation(flat = fullWidth.value),
                    )
                }
            }
        }

        compose.onNodeWithText(BODY_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("the default MP card body must be selectable", toolbar.shownCount > 0)

        compose.onNodeWithText(BODY_TEXT).performClick()
        compose.runOnIdle {
            toolbar.reset()
            fullWidth.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(BODY_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("the flat MP card body must stay selectable", toolbar.shownCount > 0)
    }

    @Test
    fun `body selection capability is constant across density and profile callback changes`() {
        val toolbar = RecordingTextContextMenuProvider()
        val metrics = mutableStateOf(BASE_METRICS)
        val profileWired = mutableStateOf(false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalDisplayMetrics provides metrics.value,
                    LocalTextContextMenuToolbarProvider provides toolbar,
                ) {
                    MessageCard(
                        message = sampleMessage(),
                        onOpenProfile = if (profileWired.value) NO_OP else null,
                    )
                }
            }
        }

        compose.onNodeWithText(BODY_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(
            "long-pressing the MP body must start a text selection (copy toolbar requested)",
            toolbar.shownCount > 0,
        )

        // One axis at a time (gate #1042): flipping both at once would only prove that TWO
        // long-presses bracketing a combined change work — each axis must hold independently.
        // Axis 1 — density flip alone: clear the selection (a plain tap inside the container),
        // change only the preset, select again.
        compose.onNodeWithText(BODY_TEXT).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            toolbar.reset()
            metrics.value = WIDE_GUTTER_METRICS
        }
        compose.waitForIdle()
        compose.onNodeWithText(BODY_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(
            "selection must not derive from the density preset",
            toolbar.shownCount > 0,
        )

        // Axis 2 — profile-callback presence alone (density now stable at the flipped preset).
        compose.onNodeWithText(BODY_TEXT).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            toolbar.reset()
            profileWired.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(BODY_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(
            "selection must not derive from a callback's presence",
            toolbar.shownCount > 0,
        )
    }

    @Test
    fun `profile tap survives on the gold creator pseudo and keeps exactly one heading`() {
        var taps = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage(), onOpenProfile = { taps++ })
            }
        }

        compose.onNodeWithTag(CREATOR_PSEUDO_TEXT_TAG).performClick()
        compose.onNodeWithContentDescription("Avatar de $AUTHOR").performClick()
        assertEquals("avatar and pseudo must both reach onOpenProfile", 2, taps)

        // The supplied creator slot owns its click AND its heading; the shared header must add none.
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun measuredBodyGutterDp(): Float = measuredGutterDp(BODY_TEXT)

    private fun measuredGutterDp(text: String): Float {
        val cardLeft = compose.onNodeWithTag(CARD_TAG)
            .fetchSemanticsNode().boundsInRoot.left
        val textLeft = compose.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        return with(compose.density) { (textLeft - cardLeft).toDp().value }
    }

    private fun measuredCardHeightDp(): Float {
        val height = compose.onNodeWithTag(CARD_TAG)
            .fetchSemanticsNode().boundsInRoot.height
        return with(compose.density) { height.toDp().value }
    }

    private fun sampleMessage(content: PostContent = paragraph(BODY_TEXT)): Post = Post(
        numreponse = 1,
        author = AUTHOR,
        date = Instant.EPOCH,
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))),
    )

    private fun longQuoteContent(): PostContent {
        val longText = buildString {
            repeat(30) { append("Ligne de citation numéro $it assez longue pour dépasser le seuil. ") }
        }
        return PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(MARKER_TEXT))),
                PostBlock.Quote(
                    author = "tinc",
                    numreponse = 42,
                    page = 1,
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(inlines = listOf(PostInline.Text(longText))),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun statefulBodyContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(inlines = listOf(PostInline.Text(MARKER_TEXT))),
            PostBlock.Spoiler(
                label = "Secret",
                content = paragraph(SPOILER_TEXT),
            ),
            longQuoteContent().blocks.last(),
        ),
    )

    private companion object {
        const val AUTHOR = "XaTriX"
        const val BODY_TEXT = "corps du message"
        const val MARKER_TEXT = "repère hors citation"
        const val SPOILER_TEXT = "contenu secret révélé"
        const val CARD_TAG = "MessageCardUnderTest"
        const val DP_TOLERANCE = 0.01f
        val NO_OP: () -> Unit = {}

        // Same values as DisplayMetrics.Comfort, restated locally so the measurements pin the
        // MECHANISM (geometry tracks whatever preset is provided), not the shipped preset values.
        val BASE_METRICS = DisplayMetrics(
            cardBodyHorizontal = 12.dp,
            cardBodyTop = 10.dp,
            cardBodyBottom = 8.dp,
            cardHeaderVertical = 6.dp,
            listRowVertical = 10.dp,
            postSpacing = 8.dp,
        )

        // Differs from BASE in cardBodyHorizontal ONLY: horizontal measurements isolate the gutter.
        val WIDE_GUTTER_METRICS = BASE_METRICS.copy(cardBodyHorizontal = 24.dp)

        // Differs from BASE in cardBodyTop/cardBodyBottom ONLY (same gutter, so no text re-wrap):
        // vertical measurements isolate the card's vertical envelope.
        val TALL_RHYTHM_METRICS = BASE_METRICS.copy(cardBodyTop = 22.dp, cardBodyBottom = 17.dp)
    }
}

/**
 * No-op stand-in for the platform text-selection [Magnifier]: under Robolectric its popup window
 * never obtains a real Surface, and the first `dismiss()` — reached whenever an observed state
 * change updates the magnifier node, e.g. a density flip during an active selection — throws an
 * NPE from `Magnifier$InternalPopupWindow.destroy`. Stubbing show/update/dismiss keeps the popup
 * from ever existing; the selection pipeline (and its [TextContextMenuProvider] requests) is
 * unaffected.
 */
@Implements(Magnifier::class)
class NoopShadowMagnifier {

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}

/**
 * Records selection-toolbar requests: the selection manager asks [LocalTextContextMenuToolbarProvider]
 * to show the copy toolbar exactly when a touch selection lands on selectable text, so this is the
 * observable behaviour of a selectable MP body (the selection highlight itself leaves no semantics
 * to assert on, and the Compose Foundation 1.11.2 this repo pins routes the selection toolbar through
 * this provider, bypassing the legacy `LocalTextToolbar`). [awaitCancellation] keeps the session
 * "shown" until the pipeline hides it, per the provider contract.
 */
private class RecordingTextContextMenuProvider : TextContextMenuProvider {
    var shownCount: Int = 0
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        shownCount++
        awaitCancellation()
    }

    fun reset() {
        shownCount = 0
    }
}
