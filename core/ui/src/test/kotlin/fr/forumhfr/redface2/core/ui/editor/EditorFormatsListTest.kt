package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/** #1417 point 5: the list is tall (about 760 dp), so the window is too: every row is laid out on screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorFormatsListTest {
    @get:Rule val compose = createComposeRule()
    private var enabled by mutableStateOf(true)
    private val formatted = mutableListOf<BbcodeAction>()

    /** ED-20 order of the format row: tag suffix, name, effect, inserted action. */
    private val formats = listOf(
        Format("bold", "Gras", "Texte en gras", BbcodeAction.Bold),
        Format("italic", "Italique", "Texte en italique", BbcodeAction.Italic),
        Format("underline", "Souligné", "Texte souligné", BbcodeAction.Underline),
        Format("strike", "Barré", "Texte barré", BbcodeAction.Strike),
        Format("link", "Lien", "Lien cliquable vers une adresse web", BbcodeAction.Url),
        Format("spoiler", "Spoiler", "Texte masqué, révélé par le lecteur", BbcodeAction.Spoiler),
        Format("color", "Couleur", "Texte en couleur : choisissez une teinte", null),
        Format("bullet", "Puce", "Puce de liste devant le texte", BbcodeAction.Bullet),
        Format("quote", "Citation", "Bloc de citation", BbcodeAction.Quote),
        Format("code", "Code", "Bloc de code", BbcodeAction.Cpp),
        Format("fixed", "Bloc fixé", "Bloc à chasse fixe, espaces conservés", BbcodeAction.Fixed),
        Format("email", "E-mail", "Lien vers une adresse e-mail", BbcodeAction.Email),
    )
    private val tints = listOf(
        Triple("color_red", "Rouge", "#FF0000"), Triple("color_blue", "Bleu", "#0000FF"),
        Triple("color_green", "Vert", "#008000"), Triple("color_orange", "Orange", "#FF6600"),
        Triple("color_grey", "Gris", "#808080"),
    )

    private data class Format(val id: String, val name: String, val effect: String, val action: BbcodeAction?)

    private fun setList() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                EditorFormatsList({ formatted += it }, enabled)
            }
        }
    }

    private fun row(id: String) = compose.onNodeWithTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + id)
    private fun rowText(format: Format, text: String) = hasText(text) and (
        hasTestTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + format.id) or
            hasAnyAncestor(hasTestTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + format.id)))

    @Test fun twelveFormatsAreListedInTheOrderOfTheFormatRowWithTheirNameAndEffect() {
        setList()
        assertEquals(12, formats.size)
        var previousTop = Float.NEGATIVE_INFINITY
        formats.forEach { format ->
            compose.onAllNodes(rowText(format, format.name)).assertCountEquals(1)
            compose.onAllNodes(rowText(format, format.effect)).assertCountEquals(1)
            val bounds = row(format.id).assertHeightIsAtLeast(56.dp).getUnclippedBoundsInRoot()
            assertTrue("${format.name} follows the previous format", bounds.top.value > previousTop)
            previousTop = bounds.top.value
        }
    }

    @Test fun eachFormatIsOneButtonThatAppliesItsOwnActionOnce() {
        setList()
        val expected = mutableListOf<BbcodeAction>()
        formats.filter { it.action != null }.forEach { format ->
            compose.onAllNodes(hasClickAction() and hasText(format.name)).assertCountEquals(1)
            row(format.id).assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(hasText(format.name) and hasText(format.effect))
                .performClick()
            expected += checkNotNull(format.action)
            compose.runOnIdle { assertEquals(expected, formatted) }
        }
    }

    // TalkBack reads « Gras, Texte en gras »: raw tags are drawn but never exposed, merged or not.
    @Test fun rawTagsAreDrawnFromTheActionsButHiddenFromAccessibility() {
        setList()
        assertEquals("[b]…[/b]", editorFormatTags(BbcodeAction.Bold))
        assertEquals("[*]", editorFormatTags(BbcodeAction.Bullet))
        assertEquals("[#FF0000]…[/#FF0000]", editorFormatTags(BbcodeAction.Color("#FF0000")))
        listOf(false, true).forEach { unmerged ->
            compose.onAllNodes(hasText("[", substring = true), useUnmergedTree = unmerged).assertCountEquals(0)
            compose.onAllNodes(hasContentDescription("[", substring = true), useUnmergedTree = unmerged)
                .assertCountEquals(0)
        }
    }

    @Test fun colourListsItsFiveTintsAs48DpButtons() {
        setList()
        // The described heading is not a button: only its tints insert.
        compose.onAllNodes(hasClickAction() and hasText("Couleur")).assertCountEquals(0)
        val expected = mutableListOf<BbcodeAction>()
        var previousLeft = Float.NEGATIVE_INFINITY
        tints.forEach { (id, name, hex) ->
            val tint = compose.onNodeWithContentDescription(name)
                .assert(hasTestTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + id))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val left = tint.getUnclippedBoundsInRoot().left.value
            assertTrue("$name follows the previous tint", left > previousLeft)
            previousLeft = left
            tint.performClick()
            expected += BbcodeAction.Color(hex)
            compose.runOnIdle { assertEquals(expected, formatted) }
        }
    }

    @Test fun disabledListRejectsEveryFormatAndTint() {
        setList()
        compose.runOnIdle { enabled = false }
        formats.filter { it.action != null }.forEach { format ->
            row(format.id).assertIsNotEnabled().performTouchInput { click() }
        }
        tints.forEach { (_, name, _) ->
            compose.onNodeWithContentDescription(name).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertTrue(formatted.isEmpty()) }
    }
}
