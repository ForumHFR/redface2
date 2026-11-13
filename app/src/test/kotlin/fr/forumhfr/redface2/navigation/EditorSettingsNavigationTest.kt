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
            assertTrue(active.last().hidesNavigationSuite())
            openEditorSettings(active)
            assertEquals(listOf(root, editor, SettingsCategoryRoute("editing")), active)
            assertFalse(active.last().hidesNavigationSuite())
            active.removeAt(active.lastIndex)
            assertSame(editor, active.last())
            assertTrue(active.last().hidesNavigationSuite())
        }
    }
}
