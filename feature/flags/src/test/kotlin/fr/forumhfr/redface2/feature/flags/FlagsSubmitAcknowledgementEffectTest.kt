package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagsSubmitAcknowledgementEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `presented acknowledgement is consumed without cancelling its display or replaying`() {
        val restoration = StateRestorationTester(compose)
        val host = SnackbarHostState()
        val consumed = mutableListOf<Long>()
        restoration.setContent {
            val request = rememberSaveable { mutableLongStateOf(1L) }
            FlagsSubmitAcknowledgementEffect(
                request = request.longValue,
                host = host,
                message = "Message publié",
                onConsumed = {
                    consumed += it
                    if (request.longValue == it) request.longValue = 0
                },
            )
        }
        compose.waitUntil { consumed == listOf(1L) }
        assertEquals("Message publié", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(listOf(1L), consumed)
    }
    @Test
    fun `remount does not replay but a later distinct success still presents`() {
        val host = SnackbarHostState()
        val request = mutableLongStateOf(1L)
        val mounted = mutableStateOf(true)
        val consumed = mutableListOf<Long>()
        compose.setContent {
            if (mounted.value) {
                FlagsSubmitAcknowledgementEffect(
                    request = request.longValue,
                    host = host,
                    message = "Message publié",
                    onConsumed = {
                        consumed += it
                        if (request.longValue == it) request.longValue = 0
                    },
                )
            }
        }
        compose.waitUntil { consumed == listOf(1L) }
        compose.runOnIdle { mounted.value = false }
        compose.waitUntil { host.currentSnackbarData == null }
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()
        assertEquals(listOf(1L), consumed)
        compose.runOnIdle { request.longValue = 2L }
        compose.waitUntil { consumed == listOf(1L, 2L) }
        assertEquals("Message publié", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `an unpresented id waits for the foreground without being consumed`() {
        val host = SnackbarHostState()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        val consumed = mutableListOf<Long>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FlagsSubmitAcknowledgementEffect(1L, host, "Message publié", onConsumed = { consumed += it })
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<Long>(), consumed)
        assertNull(host.currentSnackbarData)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { consumed == listOf(1L) }
        assertEquals("Message publié", host.currentSnackbarData?.visuals?.message)
    }

}
