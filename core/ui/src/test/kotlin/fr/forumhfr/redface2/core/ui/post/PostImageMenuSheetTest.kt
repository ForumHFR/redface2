package fr.forumhfr.redface2.core.ui.post

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #831/#1040 — pins the four entries of the shared image contextual menu: « Enregistrer » routes
 * to the save callback, « Copier l'URL » writes the clipboard, « Ouvrir dans le navigateur » fires
 * an ACTION_VIEW on the DIRECT image URL, and « Afficher en taille réelle » stays a DISABLED
 * placeholder until the fullscreen viewer (#182).
 *
 * The target URL uses the reserved `.invalid` TLD so the hero thumbnail's Coil request fails
 * fast without touching the network — the sheet never blocks on the bitmap by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostImageMenuSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val target = PostImageTarget(
        url = "https://images.invalid/photos/vacances.png",
        description = "photo",
        linkUrl = null,
    )

    private fun mount(onSave: (String) -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostImageMenuSheet(target = target, onSave = onSave, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun `the save entry routes the image URL to the save callback`() {
        val saved = mutableListOf<String>()
        mount(onSave = { saved += it })

        composeTestRule.onNodeWithText("Enregistrer l'image").performClick()

        assertEquals(listOf(target.url), saved)
    }

    @Test
    fun `the copy entry writes the image URL to the clipboard`() {
        mount()

        composeTestRule.onNodeWithText("Copier l'URL de l'image").performClick()

        val clipboard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(target.url, copied)
    }

    @Test
    fun `the browser entry fires an ACTION_VIEW on the direct image URL`() {
        mount()

        composeTestRule.onNodeWithText("Ouvrir dans le navigateur").performClick()

        val started = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(target.url, started.data.toString())
    }

    @Test
    fun `the full-size entry is a disabled placeholder`() {
        mount()

        composeTestRule.onNodeWithText("Afficher en taille réelle (à venir)")
            .assertIsNotEnabled()
    }
}
