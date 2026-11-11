package fr.forumhfr.redface2.core.ui.editor

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C8 — a focused field detached while its host-owned state survives (rotation, recreation, `key` switch)
 * keeps an extended selection that BTF2 collapses to its max during the detach. Only that bare collapse
 * is undone: any other change made in the same detach pass wins.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldDetachSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val state = TextFieldState(TEXT)
    private val generation = mutableIntStateOf(0)

    @Test
    fun `focused reversed range survives field recreation without an undo entry`() {
        recreateFocusedField(transformation = null)

        compose.runOnIdle {
            assertEquals(TEXT, state.text.toString())
            assertEquals(REVERSED, state.selection)
            assertFalse("restoring a selection must not add an undo entry", state.undoState.canUndo)
        }
    }

    @Test
    fun `text rewritten during the collapse is not given the recorded range back`() {
        // The collapse is a user edit, so the input transformation runs between record and restore.
        recreateFocusedField(transformation = InputTransformation { append("!") })

        compose.runOnIdle {
            assertEquals("$TEXT!", state.text.toString())
            assertEquals(TextRange(REVERSED.max), state.selection)
        }
    }

    @Test
    fun `caret moved during the collapse is not given the recorded range back`() {
        recreateFocusedField(transformation = InputTransformation { selection = TextRange(0) })

        compose.runOnIdle {
            assertEquals(TEXT, state.text.toString())
            assertEquals(TextRange(0), state.selection)
        }
    }

    private fun recreateFocusedField(transformation: InputTransformation?) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                key(generation.intValue) {
                    BbcodeTextField(state, "Message", Modifier.size(320.dp, 240.dp),
                        inputTransformation = transformation)
                }
            }
        }
        compose.onNode(hasSetTextAction()).requestFocus()
        // A programmatic edit can pose a reversed range; performTextInputSelection sends (min, max).
        compose.runOnIdle { state.edit { selection = REVERSED } }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(REVERSED, state.selection)
            generation.intValue++ // Same state, new field: the focused one detaches.
        }
        // The new field never had focus, so the detached one lost it during this recreation.
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}

/**
 * C8 through a REAL recreation (Robolectric `ActivityController.recreate`, as `ActivityThread` does):
 * `callActivityOnDestroy` dispatches ON_DESTROY, `WrappedComposition` disposes the composition and the
 * field's nodes detach; only then is the decor view removed from its window. Had the view lost focus
 * first (`releaseFocus`), BTF2 would collapse before the Box records and this test would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldActivityRecreationSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun registerHost() {
        // The host Activity belongs to the test source set, so it is in no merged manifest.
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, DetachSelectionHostActivity::class.java))
    }

    @Test
    fun `focused reversed range survives a real activity recreation`() {
        ActivityScenario.launch(DetachSelectionHostActivity::class.java).use { scenario ->
            compose.waitForIdle()
            lateinit var original: DetachSelectionHostActivity
            scenario.onActivity { original = it }
            val body = original.body
            compose.onNode(hasSetTextAction()).requestFocus()
            compose.runOnIdle { body.state.edit { selection = REVERSED } }
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(REVERSED, body.state.selection)
                assertTrue("the Compose view itself must hold focus", original.window.decorView.hasFocus())
                assertEquals(emptyList<Boolean>(), body.collapses)
            }

            scenario.recreate()
            compose.waitForIdle()

            scenario.onActivity {
                assertNotSame("the Activity and its composition must really be recreated", original, it)
                assertTrue(original.isChangingConfigurations)
                assertSame("the field state is retained like an editor ViewModel's", body, it.body)
            }
            compose.onNode(hasSetTextAction()).assertExists()
            compose.runOnIdle {
                // Exactly one collapse, while the view still held focus: node detach, not a prior releaseFocus.
                assertEquals(listOf(true), body.collapses)
                assertEquals(TEXT, body.state.text.toString())
                assertEquals(REVERSED, body.state.selection)
            }
        }
    }
}

/** Stands for an editor ViewModel: retained across the recreation together with its field state. */
internal class DetachSelectionBody : ViewModel() {
    val state = TextFieldState(TEXT)

    /** Window focus at each user edit; BTF2's focus-loss collapse is the only one in this test. */
    val collapses = mutableListOf<Boolean>()
}

/** Mounts the field from onCreate, like MainActivity, so `recreate()` rebuilds window and composition. */
internal class DetachSelectionHostActivity : ComponentActivity() {
    lateinit var body: DetachSelectionBody
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = viewModelFactory { initializer { DetachSelectionBody() } }
        body = ViewModelProvider(this, factory)[DetachSelectionBody::class.java]
        // Observes without editing, so the restore guard still sees BTF2's bare collapse.
        val observer = InputTransformation { body.collapses += window.decorView.hasFocus() }
        setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                BbcodeTextField(body.state, "Message", Modifier.size(320.dp, 240.dp), inputTransformation = observer)
            }
        }
    }
}

private const val TEXT = "Bonjour le forum"
private val REVERSED = TextRange(6, 1)
