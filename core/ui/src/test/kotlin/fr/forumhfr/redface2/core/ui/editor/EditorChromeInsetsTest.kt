package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic Compose inset dispatch; does not prove P20 on real API 29/34/35 windows. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorChromeInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED04 one union bottom inset and safe horizontal insets leave the measured content width`() {
        lateinit var view: View
        lateinit var density: Density
        compose.setContent {
            val localView = LocalView.current
            val localDensity = LocalDensity.current
            SideEffect { view = localView; density = localDensity }
            Box(Modifier.size(360.dp, 400.dp).windowInsetsPadding(editorHorizontalInsets())
                .windowInsetsPadding(editorBottomInsets())) {
                Box(Modifier.matchParentSize().testTag("content"))
            }
        }
        compose.waitForIdle()
        listOf(0, 200).forEach { ime ->
            val insets = with(density) {
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24.dp.roundToPx(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12.dp.roundToPx(), 0, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(),
                        Insets.of(0, 0, 24.dp.roundToPx(), 30.dp.roundToPx()))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime.dp.roundToPx()))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .build()
            }
            compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(view, insets) }
            val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
            assertEquals(324f, content.width.value, 0.5f)
            assertEquals(400f - maxOf(30, ime), content.height.value, 0.5f)
        }
    }
}
