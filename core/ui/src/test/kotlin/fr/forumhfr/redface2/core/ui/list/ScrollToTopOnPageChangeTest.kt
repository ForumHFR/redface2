package fr.forumhfr.redface2.core.ui.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #351 — guard semantics of [ScrollToTopOnPageChange] (moved verbatim from the MP screen): the first
 * Content render must NOT scroll (null guard preserves a restored position on rotation); a page
 * CHANGE scrolls to item 0; a same-page recomposition does not.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScrollToTopOnPageChangeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `first render does not scroll (null guard)`() = runTest {
        lateinit var listState: LazyListState
        composeTestRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 5)
            RedfaceTheme {
                ScrollToTopOnPageChange(listState = listState, renderedPage = 2)
                LongList(listState)
            }
        }
        composeTestRule.waitForIdle()

        // The composition starts already scrolled to item 5 (a restored position). The first render
        // leaves the guard null, so it must NOT yank back to the top.
        assertTrue(
            "first render must keep the restored position, not scroll to 0",
            listState.firstVisibleItemIndex == 5,
        )
    }

    @Test
    fun `a page change scrolls to item 0`() = runTest {
        lateinit var listState: LazyListState
        var page by mutableIntStateOf(2)
        composeTestRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 5)
            RedfaceTheme {
                ScrollToTopOnPageChange(listState = listState, renderedPage = page)
                LongList(listState)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(5, listState.firstVisibleItemIndex)

        page = 3
        composeTestRule.waitForIdle()

        assertEquals("a new page lands at the top", 0, listState.firstVisibleItemIndex)
    }

    @Test
    fun `same page does not scroll`() = runTest {
        lateinit var listState: LazyListState
        var tick by mutableIntStateOf(0)
        val page = 2
        composeTestRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 5)
            RedfaceTheme {
                // tick read so a recomposition is forced without changing renderedPage.
                @Suppress("UNUSED_EXPRESSION") tick
                ScrollToTopOnPageChange(listState = listState, renderedPage = page)
                LongList(listState)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(5, listState.firstVisibleItemIndex)

        tick = 1
        composeTestRule.waitForIdle()

        assertEquals("same page must keep the read position", 5, listState.firstVisibleItemIndex)
    }

    @androidx.compose.runtime.Composable
    private fun LongList(listState: LazyListState) {
        LazyColumn(state = listState) {
            items((0 until 30).toList(), key = { it }) { i ->
                Text(text = "item $i", modifier = Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}
