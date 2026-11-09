package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Component capture bench. The lower boxes show envelopes, not the future action bar/field. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAppBarRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf(1f)
    private val variant = mutableStateOf(0)

    @Test
    fun `ED02 ED05 ED10 app bar variants in both themes at 360 dp and landscape`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                val preset = if (scale.value == 1f) FontScalePreference.M else FontScalePreference.L
                RedfaceTheme(darkTheme = dark.value, dynamicColor = false,
                    reading = ReadingDisplaySettings(fontScale = preset)) {
                    // A fresh case must not inherit the header's remembered scroll or focus state.
                    key(dark.value, scale.value, variant.value) { AppBarSample(variant.value) }
                }
            }
        }
        listOf(false, true).forEach { isDark ->
            listOf(1f, 1.3f, 2f).forEach { fontScale ->
                (0..3).forEach { sample ->
                    capture(sample, isDark, fontScale)
                }
            }
            capture(sample = 4, isDark = isDark, fontScale = 2f)
        }
    }

    private fun capture(sample: Int, isDark: Boolean, fontScale: Float) {
        compose.runOnIdle { dark.value = isDark; scale.value = fontScale; variant.value = sample }
        if (sample == 2) compose.onNodeWithTag("bar").assertDoesNotExist()
        else assertEquals(48f, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height.value, 0.5f)
        val bottom = compose.onNodeWithTag("bottom").getUnclippedBoundsInRoot()
        assertEquals(if (sample == 2) 48f else 56f, bottom.height.value, 0.5f)
        compose.onNodeWithTag("sample").captureRoboImage(
            "src/test/screenshots/editor_app_bar_${sample}_${isDark}_$fontScale.png",
        )
    }

    @Composable
    private fun AppBarSample(variant: Int) {
        val compact = variant == 2
        val metrics = EditorChromeMetrics(compact)
        val width = when (variant) { 2 -> 760.dp; 3 -> 200.dp; else -> 360.dp }
        val count = if (variant == 4) 123 else 12
        Surface(Modifier.size(width, 180.dp).testTag("sample")) {
            Column {
                EditorAppBar(
                    stringResource(if (variant == 0) R.string.editor_app_bar_reply_title
                        else R.string.editor_app_bar_new_topic_title),
                    "Titre du sujet et contexte de rédaction", {}, {}, Modifier.testTag("bar"),
                    firstStatus = if (variant == 0) null else
                        EditorAppBarStatus(R.drawable.ic_editor_quote, "$count citations", {}, count),
                    secondStatus = if (variant == 0) null else
                        EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", {}),
                    onOpenHeader = if (variant == 0) null else ({}), compactHeight = compact,
                    headerActionLabel = stringResource(R.string.editor_app_bar_open_topic_header),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { Text("Message") }
                Surface(Modifier.fillMaxWidth().height(metrics.actionBarHeight).testTag("bottom"),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text("Barre d’action · enveloppe de référence")
                }
            }
        }
    }
}
