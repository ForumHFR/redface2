package fr.forumhfr.redface2.navigation

import androidx.navigation3.runtime.NavKey
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.TopicFormMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSettingsNavigationTest {
    @Test fun allFourEditorsPushEditingOnTheirCurrentStackAndReturnToTheSameEntry() {
        val editors = listOf(
            ForumRoute to PostEditorRoute(PostEditorMode.Reply, 23, 42, page = 1, subcat = 0),
            ForumRoute to TopicFormRoute(TopicFormMode.New, cat = 23),
            MessagesRoute to PrivateMessageReplyRoute(42, page = 1),
            MessagesRoute to PrivateMessageComposeRoute(),
        )
        editors.forEach { (root, editor) ->
            val active = mutableListOf<NavKey>(root, editor)
            assertTrue(active.hidesNavigationSuite())
            openEditorSettings(active)
            assertEquals(listOf(root, editor, SettingsCategoryRoute("editing")), active)
            assertTrue("Settings must not allow switching away from $editor", active.hidesNavigationSuite())
            active.removeAt(active.lastIndex)
            assertSame(editor, active.last())
            assertTrue(active.hidesNavigationSuite())
        }
    }

    @Test fun settingsTabKeepsNavigationVisibleAtItsRootAndInEditing() {
        val active = mutableListOf<NavKey>(SettingsRoute)
        assertFalse(active.hidesNavigationSuite())
        active.add(SettingsCategoryRoute("editing"))
        assertFalse(active.hidesNavigationSuite())
    }

    @Test fun settingsKeepNavigationHiddenWithAnEditorDeeperInTheStack() {
        val active = listOf<NavKey>(ForumRoute, TopicFormRoute(TopicFormMode.New, cat = 23),
            SettingsCategoryRoute("editing"), SettingsCategoryRoute("appearance"))
        assertTrue(active.hidesNavigationSuite())
    }

    @Test fun diagnosticsAboveEditorSettingsKeepNavigationHiddenForAllFourEditors() {
        val editors = listOf(
            ForumRoute to PostEditorRoute(PostEditorMode.Reply, 23, 42, page = 1, subcat = 0),
            ForumRoute to TopicFormRoute(TopicFormMode.New, cat = 23),
            MessagesRoute to PrivateMessageReplyRoute(42, page = 1),
            MessagesRoute to PrivateMessageComposeRoute(),
        )
        editors.forEach { (root, editor) ->
            val active = mutableListOf<NavKey>(root, editor)
            openEditorSettings(active)
            active.add(DiagnosticsRoute)
            assertTrue("Diagnostics must not allow switching away from $editor", active.hidesNavigationSuite())
        }
    }

    @Test fun diagnosticsWithoutAnEditorKeepNavigationVisible() {
        val active = mutableListOf<NavKey>(SettingsRoute, DiagnosticsRoute)
        assertFalse(active.hidesNavigationSuite())
        active.add(1, SettingsCategoryRoute("editing"))
        assertFalse(active.hidesNavigationSuite())
    }

    @Test fun topicWithoutAnEditorKeepsNavigationVisible() {
        val active = listOf<NavKey>(ForumRoute, TopicRoute(cat = 23, post = 42))
        assertFalse(active.hidesNavigationSuite())
    }

    @Test fun settingsOpenedAfterClosingTheEditorKeepNavigationVisible() {
        val active = mutableListOf<NavKey>(ForumRoute, TopicFormRoute(TopicFormMode.New, cat = 23))
        active.removeAt(active.lastIndex)
        openEditorSettings(active)
        assertFalse(active.hidesNavigationSuite())
        active.add(DiagnosticsRoute)
        assertFalse(active.hidesNavigationSuite())
    }

    @Test fun settingsBelowAnEditorDoNotHideNavigationOnDiagnostics() {
        val active = listOf<NavKey>(ForumRoute, SettingsCategoryRoute("editing"),
            TopicFormRoute(TopicFormMode.New, cat = 23), DiagnosticsRoute)
        assertFalse(active.hidesNavigationSuite())
    }

    @Test fun settingsAboveANonEditorDoNotHideNavigation() {
        val active = listOf<NavKey>(ForumRoute, SettingsCategoryRoute("editing"))
        assertFalse(active.hidesNavigationSuite())
    }
}
