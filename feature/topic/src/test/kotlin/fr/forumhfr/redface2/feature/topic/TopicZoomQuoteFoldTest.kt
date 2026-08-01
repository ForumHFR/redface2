package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #946 — pinching on an EXPANDED long quote must not fold it back (tinc, DEV thread, v240).
 * Reproduces the field scenario: a real [PostRenderer] long quote inside the magnifier harness,
 * expanded by tap, then a pinch centred on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicZoomQuoteFoldTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var zoomState: TopicZoomState

    private fun longQuoteContent(): PostContent {
        val longText = buildString {
            repeat(30) { append("Ligne de citation numéro $it assez longue pour dépasser le seuil. ") }
        }
        return PostContent(
            blocks = listOf(
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
                PostBlock.Paragraph(inlines = listOf(PostInline.Text("réponse au-dessous"))),
            ),
        )
    }

    @Test
    fun `pinching on an expanded long quote keeps it expanded`() {
        compose.setContent {
            RedfaceTheme {
                CompositionLocalProvider(LocalFoldLongQuotes provides true) {
                    val scope = rememberCoroutineScope()
                    val listState = remember { LazyListState() }
                    zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
                    val isZoomed by remember(zoomState) { androidx.compose.runtime.derivedStateOf { zoomState.zoomed } }
                    CompositionLocalProvider(LocalTopicZoomed provides isZoomed) {
                    Box(
                        Modifier
                            .size(360.dp, 600.dp)
                            .testTag("zoom")
                            .topicMagnifier(zoomState, listState),
                    ) {
                        LazyColumn(state = listState) {
                            item {
                                // Parité TopicScreen post-#946 : selectable est FIXE. Le flip
                                // zoom-dépendant était LE mécanisme du bug (swap structurel du
                                // SelectionContainer -> rememberSaveable jeté) — reproduit ROUGE
                                // avec `selectable = !LocalTopicZoomed.current` avant le fix.
                                PostRenderer(
                                    content = longQuoteContent(),
                                    selectable = true,
                                )
                            }
                            items(count = 50) { i ->
                                Text("post $i", Modifier.fillMaxWidth())
                            }
                        }
                    }
                    }
                }
            }
        }
        // Expand the folded long quote (the frame toggles on tap).
        compose.onNodeWithText("Déplier", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Replier", substring = true).assertExists()

        // Pinch centred on the expanded quote (both fingers on it) — field scenario.
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 150f))
            down(1, center + Offset(0f, 150f))
            repeat(10) { i ->
                val gap = 300f + 450f * (i + 1) / 10
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertTrue("the pinch must engage the zoom", zoomState.scale.floatValue > 1.2f)
        compose.onNodeWithText("Replier", substring = true).assertExists()
    }
}
