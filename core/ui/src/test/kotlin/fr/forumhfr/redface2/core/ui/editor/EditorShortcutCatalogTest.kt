package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorShortcutCatalogTest {
    @Test
    fun `closed catalogue has one entry and a stable distinct ID per choice`() {
        assertEquals(EditorShortcut.entries.toSet(), editorShortcutCatalog.map { it.choice }.toSet())
        assertEquals(22, editorShortcutCatalog.size)
        assertEquals(22, EditorShortcut.entries.map { it.id }.distinct().size)
        assertEquals("search_smileys", EditorShortcut.SEARCH_SMILEYS.id)
        assertEquals("bullet", EditorShortcut.BULLET.id)
        assertEquals("email", EditorShortcut.EMAIL.id)
        assertEquals(EditorShortcut.NONE, EditorShortcut.fromId(null))
        assertEquals(EditorShortcut.NONE, EditorShortcut.fromId("unknown_future_action"))
        EditorShortcut.entries.forEach { assertEquals(it, EditorShortcut.fromId(it.id)) }
    }

    @Test
    fun `existing persisted choices keep their IDs when the presentation order changes`() {
        val choices = listOf(EditorShortcut.NONE, EditorShortcut.BOLD, EditorShortcut.ITALIC, EditorShortcut.UNDERLINE,
            EditorShortcut.STRIKE, EditorShortcut.QUOTE, EditorShortcut.CODE, EditorShortcut.FIXED,
            EditorShortcut.SPOILER, EditorShortcut.LINK, EditorShortcut.RED, EditorShortcut.BLUE, EditorShortcut.GREEN,
            EditorShortcut.ORANGE, EditorShortcut.GREY, EditorShortcut.UPLOAD_IMAGE, EditorShortcut.IMAGE_URL,
            EditorShortcut.IMAGE_SELECTION, EditorShortcut.SEARCH_SMILEYS, EditorShortcut.PERSONAL_SMILEY)
        val ids = listOf("none", "bold", "italic", "underline", "strike", "quote", "code", "fixed", "spoiler", "link",
            "color_red", "color_blue", "color_green", "color_orange", "color_grey", "upload_image", "image_url",
            "image_selection", "search_smileys", "personal_smiley")
        assertEquals(choices, ids.map { EditorShortcut.fromId(it) })
    }

    @Test
    fun `ED36 surface table is independent of image capabilities`() {
        val common = EditorShortcut.entries.toSet() - setOf(EditorShortcut.NONE, EditorShortcut.UPLOAD_IMAGE,
            EditorShortcut.IMAGE_URL, EditorShortcut.IMAGE_SELECTION)
        EditorShortcutSurface.entries.forEach { surface ->
            val images = when (surface) {
                EditorShortcutSurface.POST, EditorShortcutSurface.TOPIC ->
                    setOf(EditorShortcut.UPLOAD_IMAGE, EditorShortcut.IMAGE_URL)
                EditorShortcutSurface.PRIVATE_MESSAGE_REPLY, EditorShortcutSurface.PRIVATE_MESSAGE_COMPOSE ->
                    setOf(EditorShortcut.UPLOAD_IMAGE, EditorShortcut.IMAGE_SELECTION)
                EditorShortcutSurface.QUICK_REPLY -> emptySet()
            }
            assertEquals(common + images, EditorShortcut.entries.filter { it.isAvailableOn(surface) }.toSet())
        }
        assertFalse(EditorShortcut.IMAGE_SELECTION.isAvailableOn(EditorShortcutSurface.POST))
    }

    @Test
    fun `formats and drawn colors share exact toolbar payloads without a Color sublevel`() {
        assertEquals(listOf(BbcodeAction.Bold, BbcodeAction.Italic, BbcodeAction.Underline, BbcodeAction.Strike,
            BbcodeAction.Url, BbcodeAction.Spoiler, BbcodeAction.Bullet, BbcodeAction.Quote, BbcodeAction.Cpp,
            BbcodeAction.Fixed, BbcodeAction.Email),
            editorShortcutFormats.map { it.bbcode })
        assertEquals(listOf(EditorShortcut.BOLD, EditorShortcut.ITALIC, EditorShortcut.UNDERLINE,
            EditorShortcut.STRIKE, EditorShortcut.LINK, EditorShortcut.SPOILER),
            editorShortcutFormatsBeforeColor.map { it.choice })
        assertEquals(listOf(EditorShortcut.BULLET, EditorShortcut.QUOTE, EditorShortcut.CODE,
            EditorShortcut.FIXED, EditorShortcut.EMAIL), editorShortcutFormatsAfterColor.map { it.choice })
        assertEquals(listOf("#FF0000", "#0000FF", "#008000", "#FF6600", "#808080"),
            editorShortcutColors.map { (it.bbcode as BbcodeAction.Color).colorHex })
        assertTrue(editorShortcutColors.all { it.iconRes == null })
        assertEquals(BbcodeAction.PersonalSmiley,
            editorShortcutCatalog.single { it.choice == EditorShortcut.PERSONAL_SMILEY }.bbcode)
    }
}
