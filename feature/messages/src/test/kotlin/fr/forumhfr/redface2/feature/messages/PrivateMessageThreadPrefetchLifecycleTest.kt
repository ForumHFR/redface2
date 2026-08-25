package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose proof for the ADR-013 definition of an open private conversation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateMessageThreadPrefetchLifecycleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `composed conversation starts prefetch only at RESUMED and stops on pause`() {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val activations = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PrivateMessageThreadPrefetchLifecycleGate(activations::add)
            }
        }

        compose.waitForIdle()
        assertEquals("composition below RESUMED must stay inert", emptyList<Boolean>(), activations)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(listOf(true), activations)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        assertEquals("pause must cancel without starting another group", listOf(true, false), activations)
    }

    @Test
    fun `disposing a resumed conversation stops prefetch and lifecycle changes cannot restart it`() {
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val composed = mutableStateOf(true)
        val activations = mutableListOf<Boolean>()
        compose.setContent {
            if (composed.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    PrivateMessageThreadPrefetchLifecycleGate(activations::add)
                }
            }
        }

        compose.waitForIdle()
        assertEquals(listOf(true), activations)

        compose.runOnIdle { composed.value = false }
        compose.waitForIdle()
        assertEquals(listOf(true, false), activations)

        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals("a disposed screen must never restart prefetch", listOf(true, false), activations)
    }

    private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = initialState }
        override val lifecycle: Lifecycle
            get() = registry
    }
}
