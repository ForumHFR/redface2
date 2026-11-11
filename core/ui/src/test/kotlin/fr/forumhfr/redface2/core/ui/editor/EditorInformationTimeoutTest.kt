package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorInformationTimeoutTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = EditorDraftOfferFixture()

    @Test fun `opted in notice retires at five seconds without a decision or focus return`() {
        content()
        val key = notice(5_000L)
        advance(4_999L)
        compose.runOnIdle { assertEquals(key, fixture.events.visibleEvent?.key) }
        advance(1L)
        compose.runOnIdle {
            assertNull(fixture.events.visibleEvent)
            assertTrue(fixture.events.state.events.isEmpty())
            assertTrue(fixture.available)
            assertFalse(fixture.host.draftOfferOpen)
            assertEquals(0, fixture.returns)
            assertTrue(fixture.calls.isEmpty())
            fixture.host.submitActions.onSubmit()
            assertEquals(listOf("arm"), fixture.calls) // The retired information never consumes a later gesture.
        }
    }

    @Test fun `accessibility recommendation extends the notice and declares text and controls`() {
        var recommended = false
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long,
                containsIcons: Boolean, containsText: Boolean, containsControls: Boolean): Long {
                if (originalTimeoutMillis != 5_000L) return originalTimeoutMillis
                assertTrue(containsText && containsControls)
                recommended = true
                return 10_000L
            }
        }
        content(manager)
        val key = notice(5_000L)
        advance(5_000L)
        compose.runOnIdle {
            assertTrue(recommended)
            assertEquals(key, fixture.events.visibleEvent?.key)
        }
        advance(4_999L)
        compose.runOnIdle { assertEquals(key, fixture.events.visibleEvent?.key) }
        advance(1L)
        compose.runOnIdle {
            assertNull(fixture.events.visibleEvent)
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun `a shorter accessibility recommendation never reduces the base timeout`() {
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long,
                containsIcons: Boolean, containsText: Boolean, containsControls: Boolean) = 1_000L
        }
        content(manager)
        val key = notice(5_000L)
        advance(4_999L)
        compose.runOnIdle { assertEquals(key, fixture.events.visibleEvent?.key) }
        advance(1L)
        compose.runOnIdle { assertNull(fixture.events.visibleEvent) }
    }

    @Test fun `an earlier gesture retires the notice and its timer cannot acknowledge the next delivery`() {
        content()
        notice(5_000L)
        advance(1_000L)
        compose.runOnIdle {
            fixture.host.barActions.onSmileys()
            assertNull(fixture.events.visibleEvent)
            assertNull(fixture.host.surface) // ED-44 consumes this earlier gesture.
        }
        val replacement = notice(null)
        advance(10_000L)
        compose.runOnIdle {
            assertEquals(replacement, fixture.events.visibleEvent?.key)
            assertTrue(fixture.calls.isEmpty())
            assertEquals(0, fixture.returns)
        }
    }

    @Test fun `ordinary information has no automatic retirement`() {
        content()
        val key = notice(null)
        advance(60_000L)
        compose.runOnIdle {
            assertEquals(key, fixture.events.visibleEvent?.key)
            fixture.host.submitActions.onSubmit()
            assertNull(fixture.events.visibleEvent)
            assertTrue(fixture.calls.isEmpty())
        }
    }

    private fun content(manager: AccessibilityManager? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides manager) { fixture.Content() }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun notice(timeoutMillis: Long?): EventKey {
        val key = compose.runOnIdle { fixture.notice(timeoutMillis) }
        compose.mainClock.advanceTimeByFrame() // Start the effect for the newly displayed delivery.
        compose.waitForIdle()
        return key
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        compose.waitForIdle()
    }
}
