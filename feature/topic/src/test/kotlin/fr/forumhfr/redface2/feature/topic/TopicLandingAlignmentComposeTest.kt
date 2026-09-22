package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1300 — lifecycle regression around the real composition-local [TopicListAlignment] gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class TopicLandingAlignmentComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a no-scroll resolution stays persistable after the alignment object is recreated`() {
        val mounted = mutableStateOf(true)
        val landing = mutableStateOf<TopicUiState.Landing>(
            TopicUiState.Landing.Pending(id = 12, page = 2),
        )
        lateinit var listState: LazyListState
        lateinit var alignment: TopicListAlignment

        compose.setContent {
            listState = rememberLazyListState(
                initialFirstVisibleItemIndex = 7,
                initialFirstVisibleItemScrollOffset = 19,
            )
            if (mounted.value) {
                alignment = remember { TopicListAlignment() }
                TopicLandingAlignmentEffect(
                    landing = landing.value,
                    canonicalPage = 2,
                    isLoaded = true,
                    alignment = alignment,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(240.dp),
                ) {
                    items((0..20).toList()) {
                        Box(Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertFalse(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))

        // LandingResolvedWithoutScroll performs no LazyListState movement; its acknowledgement
        // alone opens persistence on the coordinates that are already visible.
        compose.runOnIdle {
            landing.value = TopicUiState.Landing.Applied(id = 12, page = 2)
        }
        compose.waitForIdle()
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))
        val firstAlignment = alignment

        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()

        assertNotSame(firstAlignment, alignment)
        val swipeDeparture = TopicScrollAnchor(
            index = listState.firstVisibleItemIndex,
            offset = listState.firstVisibleItemScrollOffset,
        ).takeIf {
            alignment.shouldPersist(canonicalPage = 2, isLoaded = true)
        }
        assertEquals(TopicScrollAnchor(index = 7, offset = 19), swipeDeparture)
    }
}
