package fr.forumhfr.redface2.core.ui.editor

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1512 — parent edits must not save full-text copies in every undo entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldSavedStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tenParentInsertionsKeepSavedStateBelowTwoTextCopies() {
        val initialText = "0123456789abcdefghi\n".repeat(1_000)
        val value = mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length / 2)))
        lateinit var registry: SaveableStateRegistry
        composeTestRule.setContent {
            val hostRegistry = checkNotNull(LocalSaveableStateRegistry.current)
            registry = remember {
                SaveableStateRegistry(restoredValues = null, canBeSaved = hostRegistry::canBeSaved)
            }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.size(320.dp, 240.dp)) {
                            BbcodeTextField(
                                value = value.value,
                                onValueChange = { value.value = it },
                                label = "Message",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val singleTextBytes = parcelSize(Bundle().apply { putString("text", initialText) })
        val initialSavedBytes = composeTestRule.runOnIdle { savedStateSize(registry) }
        assertTrue("the registry must save the initial text", initialSavedBytes >= singleTextBytes)

        repeat(10) {
            composeTestRule.runOnIdle {
                val current = value.value
                val caret = current.selection.end
                val smiley = " :jap: "
                value.value = TextFieldValue(
                    text = current.text.substring(0, caret) + smiley + current.text.substring(caret),
                    selection = TextRange(caret + smiley.length),
                )
            }
            composeTestRule.waitForIdle()
            val semantics = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
            assertEquals(value.value.text, semantics[SemanticsProperties.EditableText].text)
            assertEquals(value.value.selection, semantics[SemanticsProperties.TextSelectionRange])
        }

        val savedBytes = composeTestRule.runOnIdle { savedStateSize(registry) }
        // Two parcelled text copies allow small undo records and metadata, but reject even one full-text entry.
        assertTrue(
            "saved state is $savedBytes bytes; expected less than ${2 * singleTextBytes}",
            savedBytes < 2 * singleTextBytes,
        )
    }

    private fun savedStateSize(registry: SaveableStateRegistry): Int {
        val bundle = Bundle()
        registry.performSave().forEach { (key, values) ->
            // Parcel writes heterogeneous saveable values as a list, without Java serialization.
            @Suppress("UNCHECKED_CAST")
            bundle.putParcelableArrayList(key, ArrayList(values) as ArrayList<Parcelable?>)
        }
        return parcelSize(bundle)
    }

    private fun parcelSize(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
