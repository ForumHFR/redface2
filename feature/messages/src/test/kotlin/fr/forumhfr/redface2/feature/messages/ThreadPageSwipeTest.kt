package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1041 — characterization of the minimal in-place MP pager gesture. The callback is the same
 * `selectPage(Int)` sink used by the buttons; these tests intentionally cover no topic cache,
 * prefetch, anchor, stale-generation or slide-out machinery because the MP pager has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadPageSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a committed forward swipe selects the next page in place`() {
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = mutableStateOf(2),
            totalPages = 5,
            isRefreshing = mutableStateOf(false),
            onSelectPage = selectedPages::add,
        )

        swipeLeft()

        assertEquals(listOf(3), selectedPages)
    }

    @Test
    fun `outward swipes are inert on the first and last pages`() {
        val currentPage = mutableStateOf(1)
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = currentPage,
            totalPages = 5,
            isRefreshing = mutableStateOf(false),
            onSelectPage = selectedPages::add,
        )

        swipeRight()
        assertEquals(emptyList<Int>(), selectedPages)

        compose.runOnIdle { currentPage.value = 5 }
        compose.waitForIdle()
        swipeLeft()

        assertEquals(emptyList<Int>(), selectedPages)
    }

    @Test
    fun `the loading gate is read again after refresh settles`() {
        val isRefreshing = mutableStateOf(true)
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = mutableStateOf(2),
            totalPages = 5,
            isRefreshing = isRefreshing,
            onSelectPage = selectedPages::add,
        )

        swipeLeft()
        assertEquals("swipe must be inert while loading", emptyList<Int>(), selectedPages)

        compose.runOnIdle { isRefreshing.value = false }
        compose.waitForIdle()
        swipeLeft()

        assertEquals("the same composition must re-arm", listOf(3), selectedPages)
    }

    private fun setSwipeContent(
        currentPage: State<Int>,
        totalPages: Int,
        isRefreshing: State<Boolean>,
        onSelectPage: (Int) -> Unit,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val swipeModifier = rememberThreadSwipeModifier(
                    renderedPage = currentPage.value,
                    totalPages = totalPages,
                    isRefreshing = isRefreshing.value,
                    onSelectPage = onSelectPage,
                )
                Box(
                    modifier = Modifier
                        .size(360.dp, 600.dp)
                        .testTag(PAGE_TAG)
                        .then(swipeModifier),
                )
            }
        }
    }

    private fun swipeLeft() {
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(center)
            repeat(8) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private fun swipeRight() {
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(center)
            repeat(8) { moveBy(0, Offset(60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PAGE_TAG = "private_thread_page_swipe"
    }
}
