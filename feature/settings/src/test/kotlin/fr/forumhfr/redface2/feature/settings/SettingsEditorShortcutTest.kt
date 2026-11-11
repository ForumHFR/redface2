package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
class SettingsEditorShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `closed catalogue is in editing with NONE icons restrictions and searchable choices`() {
        lateinit var catalogue: List<SettingsCatalogueSection>
        compose.setContent {
            RedfaceTheme {
                catalogue = buildSettingsCatalogue(SettingsState(), {}, StartScreenSettingsState(), {},
                    onOpenProxy = {}, onOpenMaintenance = {}, onOpenDisplay = {}, onOpenImages = {},
                    onOpenAccountAbout = {}, onOpenBlacklist = {})
            }
        }
        compose.waitForIdle()
        val rows = catalogue.single { it.id == "editing" }.items.filter {
            it.searchable.id.startsWith("editor_shortcut_")
        }
        assertEquals(EditorShortcut.entries.map { "editor_shortcut_${it.id}" }.toSet(),
            rows.map { it.searchable.id }.toSet())
        assertEquals("Aucun", rows.first().searchable.title)
        assertEquals("Post et sujet",
            rows.single { it.searchable.id == "editor_shortcut_image_url" }.searchable.description)
        assertEquals("Éditeurs MP",
            rows.single { it.searchable.id == "editor_shortcut_image_selection" }.searchable.description)
        rows.filterNot { it.searchable.id in setOf("editor_shortcut_none", "editor_shortcut_image_url",
            "editor_shortcut_image_selection") }.forEach { assertNull(it.searchable.description) }
        val searchable = catalogue.map { it.toSearchable() }
        assertEquals(rows.map { it.searchable.id }, filterSettingsSections(searchable, "raccourci")
            .flatMap { it.items }.map { it.id })
        assertTrue(filterSettingsSections(searchable, "smiley perso").flatMap { it.items }
            .any { it.id == "editor_shortcut_personal_smiley" })
        assertEquals(rows.map { it.searchable.id }, filterSettingsSections(searchable, "raccourci de la barre")
            .flatMap { it.items }.map { it.id })
        rows.forEach { assertTrue("édition" !in it.searchable.keywords) }
        // The full group title still legitimately matches this substring; no standalone keyword is needed.
        assertEquals(rows.map { it.searchable.id }, filterSettingsSections(searchable, "édition")
            .flatMap { it.items }.filter { it.isEditorShortcut }.map { it.id })
    }

    @Test fun `filtered shortcut retains a named radio group when NONE is absent`() {
        compose.setContent {
            RedfaceTheme {
                SettingsRoot(SettingsState(), {}, StartScreenSettingsState(), {},
                    onOpenProxy = {}, onOpenMaintenance = {}, onOpenDisplay = {}, onOpenImages = {},
                    onOpenAccountAbout = {}, onOpenBlacklist = {}, onOpenCategory = {})
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.settings_search_open)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("smiley perso")
        val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        compose.onAllNodes(group).assertCountEquals(1)
        compose.onNodeWithText("Raccourci de la barre d’édition").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(hasAnyAncestor(group))
        compose.onNodeWithText("Smiley perso").assertIsDisplayed().assert(hasAnyAncestor(group)).performClick()
        compose.onNodeWithText("Aucun").assertDoesNotExist()
    }

    @Test fun `picks are explicit intents NONE is selectable and failure is visible on the restored choice`() {
        val state = mutableStateOf(SettingsState(editorShortcut = EditorShortcut.BLUE))
        val intents = mutableListOf<SettingsIntent>()
        compose.setContent {
            RedfaceTheme {
                val rows = editorShortcutRows(state.value, intents::add)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EditorShortcutGroup { rows.forEach { it.render() } }
                }
            }
        }
        compose.onNodeWithText("Bleu").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Smiley perso").performScrollTo().performClick()
        assertEquals(listOf(SettingsIntent.SetEditorShortcut(EditorShortcut.PERSONAL_SMILEY)), intents)
        compose.runOnIdle { state.value = state.value.copy(editorShortcutError = true) }
        compose.onNodeWithText("Bleu").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Impossible d’enregistrer le raccourci. Le choix précédent est conservé.")
            .performScrollTo().assertExists()
        compose.onNodeWithText("Aucun").performScrollTo().performClick()
        assertEquals(SettingsIntent.SetEditorShortcut(EditorShortcut.NONE), intents.last())
        compose.runOnIdle { state.value = state.value.copy(isUpdatingEditorShortcut = true) }
        compose.onNodeWithText("Aucun").assertIsNotEnabled()
    }
}
